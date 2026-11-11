"""Real Edge/Playwright UI tests; isolated in-memory API injection, not cloud tests.
No installation, account, backend file or database required.
"""
import copy
import json
from pathlib import Path
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
import unittest
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]


class QuietHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, *_args):
        pass


def submission(index, status='pending', published=False):
    return dict(id=f's-{index}', title=f'图书馆的第 {index} 个傍晚', category='WARMTH',
                authorName='测试投稿者', status=status, published=published,
                createdAt='2026-11-10T08:00:00Z', updatedAt='2026-11-10T08:00:00Z',
                consentAt='2026-11-10T07:59:00Z', consentVersion='2026-11-10-v1',
                reviewedAt=None, reviewerNote='', content='晚自习结束后，窗边还留着一盏灯。\n\n这是隔离测试故事，不是真实投稿。')


class BrowserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), QuietHandler)
        cls.thread = Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f'http://127.0.0.1:{cls.server.server_port}/'
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(channel='msedge', headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.context = self.browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        self.page = self.context.new_page()
        self.page.set_default_timeout(3000)
        self.requests, self.errors, self.console = [], [], []
        self.denial = None
        self.fail_action = None
        self.items = [submission(i) for i in range(1, 22)] + [submission(30, 'approved', True),
                       submission(31, 'approved', False), submission(32, 'rejected')]
        self.items[0]['title'] = '<img src=x onerror="window.pwned=1">'
        self.items[0]['content'] = '<script>window.pwned=1</script>\n<img src=x onerror="window.pwned=1">\n纯文本正文'
        self.items[-1]['reviewerNote'] = '<b>内部备注不是 HTML</b>'
        self.page.on('pageerror', lambda error: self.errors.append(str(error)))
        self.page.on('console', lambda msg: self.console.append(msg.text))
        self.context.route('**/api', self.api_route)
        self.context.add_init_script("""
            Storage.prototype.setItem = () => { throw new Error('persistent storage forbidden'); };
            window.addEventListener('securitypolicyviolation', () => { window.cspViolation = true; });
        """)
        self.page.goto(self.url)

    def tearDown(self):
        self.assertEqual(self.errors, [])
        self.assertIsNone(self.page.evaluate('window.cspViolation'))
        self.assertFalse(any('test-only-password' in message or 'browser-memory-token' in message for message in self.console))
        self.context.close()

    def api_route(self, route):
        request = route.request
        body = request.post_data_json
        self.requests.append(copy.deepcopy(body))
        action, payload = body['action'], body['payload']
        self.assertEqual(set(body), {'action', 'payload'})
        self.assertNotIn('uid', payload)
        if action == 'adminLogin':
            self.assertNotIn('authorization', request.headers)
            route.fulfill(json={'ok': True, 'data': {'accessToken': 'browser-memory-token', 'expiresIn': 3600}})
            return
        self.assertEqual(request.headers.get('authorization'), 'Bearer browser-memory-token')
        if self.denial:
            route.fulfill(status=self.denial, json={'ok': False, 'error': {'code': 'FORBIDDEN', 'message': 'untrusted server text'}})
            return
        if self.fail_action == action:
            route.fulfill(status=409, json={'ok': False, 'error': {'code': 'STATE_CONFLICT', 'message': '<b>do not render</b>'}})
            return
        if action == 'getPendingStories':
            self.assertEqual(payload['limit'], 20)
            items = [x for x in self.items if payload['status'] == 'all' or x['status'] == payload['status']]
            start = int(payload.get('cursor', '0'))
            data = {'items': [{k: v for k, v in x.items() if k in ('id', 'title', 'category', 'authorName', 'status', 'createdAt', 'published')}
                              for x in items[start:start + 20]],
                    'nextCursor': str(start + 20) if start + 20 < len(items) else None,
                    'counts': {s: len([x for x in self.items if x['status'] == s]) for s in ('pending', 'approved', 'rejected')}}
        else:
            item = next(x for x in self.items if x['id'] == payload['id'])
            if action == 'getSubmissionDetail':
                data = item
            elif action == 'approveStory':
                self.assertEqual(set(payload), {'id'})
                item.update(status='approved', published=True, publishedStoryId='story-cloud-test-1')
                data = {k: item[k] for k in ('id', 'status', 'publishedStoryId')}
            elif action == 'rejectStory':
                item.update(status='rejected', reviewerNote=payload.get('note', ''))
                data = {'id': item['id'], 'status': 'rejected'}
            elif action == 'deletePublishedStory':
                item.update(published=False, reviewerNote=payload.get('note', ''))
                data = {'id': item['id'], 'withdrawn': True}
            else:
                self.fail('unexpected action')
        route.fulfill(json={'ok': True, 'data': data})

    def login(self):
        self.page.locator('#username').fill('test-admin')
        self.page.locator('#password').fill('test-only-password')
        self.page.locator('#login-submit').click()
        expect(self.page.locator('#workspace')).to_be_visible()
        expect(self.page.locator('#count-pending')).to_have_text('21')
        expect(self.page.locator('#password')).to_have_value('')

    def open_first(self):
        self.page.locator('#story-rows button').first.click()
        expect(self.page.locator('#detail-body')).to_be_visible()

    def calls(self, action):
        return [x for x in self.requests if x['action'] == action]

    def test_login_pagination_filter_manual_refresh_no_polling(self):
        expect(self.page.locator('#environment')).to_have_text('本地Mock')
        self.login()
        self.assertEqual(self.page.locator('#story-rows tr').count(), 20)
        self.page.locator('#next-page').click()
        expect(self.page.locator('#page-info')).to_have_text('第 2 页')
        self.assertEqual(self.page.locator('#story-rows tr').count(), 1)
        self.assertEqual(self.calls('getPendingStories')[-1]['payload']['cursor'], '20')
        expect(self.page.locator('#next-page')).to_be_disabled()
        self.page.locator('#previous-page').click()
        expect(self.page.locator('#page-info')).to_have_text('第 1 页')
        self.page.locator('[data-status="approved"]').click()
        expect(self.page.locator('#story-rows tr')).to_have_count(2)
        expect(self.page.locator('#story-rows')).to_contain_text('已撤下')
        self.assertNotIn('cursor', self.calls('getPendingStories')[-1]['payload'])
        count = len(self.requests)
        self.page.wait_for_timeout(400)
        self.assertEqual(len(self.requests), count)
        self.page.locator('#refresh').click()
        expect(self.page.locator('#story-rows tr')).to_have_count(2)
        self.assertEqual(len(self.requests), count + 1)

    def test_plain_text_and_approve_confirmation_double_click(self):
        self.login()
        self.open_first()
        expect(self.page.locator('#story-content')).to_contain_text('<script>')
        self.assertEqual(self.page.locator('#story-content script, #story-content img, #detail-title img').count(), 0)
        self.assertIsNone(self.page.evaluate('window.pwned'))
        self.page.locator('#approve').click()
        expect(self.page.locator('#confirm-dialog')).to_be_visible()
        self.assertEqual(self.calls('approveStory'), [])
        self.page.locator('#confirm-cancel').click()
        self.assertEqual(self.calls('approveStory'), [])
        self.page.locator('#approve').click()
        self.page.locator('#confirm-submit').evaluate('(button) => { button.click(); button.click(); }')
        expect(self.page.locator('#count-pending')).to_have_text('20')
        self.assertEqual(len(self.calls('approveStory')), 1)
        expect(self.page.locator('#detail-dialog')).not_to_be_visible()

    def test_rejection_internal_note_limit(self):
        self.login()
        self.open_first()
        self.page.locator('#reject').click()
        self.assertEqual(self.calls('rejectStory'), [])
        expect(self.page.locator('#confirm-description')).to_contain_text('不会公开')
        expect(self.page.locator('#review-note')).to_have_attribute('maxlength', '500')
        self.page.locator('#review-note').fill('内部复核：请勿公开此备注。')
        expect(self.page.locator('#note-count')).to_contain_text('/ 500')
        self.page.locator('#confirm-submit').click()
        expect(self.page.locator('#count-rejected')).to_have_text('2')
        self.assertEqual(self.calls('rejectStory')[0]['payload'], {'id': 's-1', 'note': '内部复核：请勿公开此备注。'})
        self.page.locator('[data-status="rejected"]').click()
        self.open_first()
        expect(self.page.locator('#reviewer-note')).to_have_text('内部复核：请勿公开此备注。')
        expect(self.page.locator('#approve')).not_to_be_visible()

    def test_withdraw_confirmation_submission_id_and_no_republish(self):
        self.login()
        self.page.locator('[data-status="approved"]').click()
        self.open_first()
        expect(self.page.locator('#approve')).not_to_be_visible()
        self.page.locator('#withdraw').click()
        expect(self.page.locator('#confirm-description')).to_contain_text('不能重新发布')
        self.page.keyboard.press('Escape')
        self.assertEqual(self.calls('deletePublishedStory'), [])
        self.page.locator('#withdraw').click()
        self.page.locator('#confirm-submit').click()
        expect(self.page.locator('#detail-dialog')).not_to_be_visible()
        expect(self.page.locator('#count-approved')).to_have_text('2')
        self.assertEqual(self.calls('deletePublishedStory')[0]['payload'], {'id': 's-30'})
        self.open_first()
        expect(self.page.locator('#withdraw')).not_to_be_visible()
        expect(self.page.locator('#approve')).not_to_be_visible()

    def test_401_and_403_return_to_login_and_clear_private_content(self):
        for status in (401, 403):
            self.denial = None
            self.login()
            self.open_first()
            self.page.locator('#detail-close').click()
            self.denial = status
            self.page.locator('#refresh').click()
            expect(self.page.locator('#login-view')).to_be_visible()
            expect(self.page.locator('#workspace')).not_to_be_visible()
            self.assertEqual(self.page.locator('#story-rows tr').count(), 0)
            expect(self.page.locator('#story-content')).to_be_empty()
            expect(self.page.locator('#login-message')).not_to_have_text('')
            expect(self.page.locator('#login-message')).not_to_contain_text('untrusted')

    def test_logout_reload_and_pagehide_forget_session(self):
        self.login()
        self.page.reload()
        expect(self.page.locator('#login-view')).to_be_visible()
        before = len(self.requests)
        self.page.wait_for_timeout(100)
        self.assertEqual(len(self.requests), before)
        self.login()
        self.page.evaluate("window.dispatchEvent(new Event('pagehide'))")
        expect(self.page.locator('#login-view')).to_be_visible()
        self.login()
        self.page.locator('#logout').click()
        expect(self.page.locator('#login-view')).to_be_visible()
        expect(self.page.locator('#username')).to_have_value('')
        expect(self.page.locator('#password')).to_have_value('')

    def test_conflict_does_not_succeed_or_retry(self):
        self.login()
        self.open_first()
        self.fail_action = 'approveStory'
        self.page.locator('#approve').click()
        self.page.locator('#confirm-submit').click()
        expect(self.page.locator('#confirm-message')).to_contain_text('刷新')
        expect(self.page.locator('#confirm-dialog')).to_be_visible()
        self.assertEqual(len(self.calls('approveStory')), 1)
        expect(self.page.locator('#count-pending')).to_have_text('21')

    def test_empty_state_and_failed_login(self):
        self.context.unroute('**/api')
        self.context.route('**/api', lambda route: route.fulfill(status=401, json={'ok': False, 'error': {'code': 'BAD_LOGIN', 'message': 'test-only-password'}}))
        self.page.locator('#username').fill('test-admin')
        self.page.locator('#password').fill('test-only-password')
        self.page.locator('#login-submit').click()
        expect(self.page.locator('#login-message')).to_contain_text('账号或密码')
        expect(self.page.locator('#password')).to_have_value('')
        self.context.unroute('**/api')
        self.context.route('**/api', self.api_route)
        self.login()
        self.items = []
        self.page.locator('#refresh').click()
        expect(self.page.locator('#empty-state')).to_be_visible()
        expect(self.page.locator('#next-page')).to_be_disabled()

    def test_production_empty_config_fails_closed_without_request(self):
        def static_route(route):
            path = route.request.url.split('/admin/', 1)[-1] or 'index.html'
            file = ROOT / path
            if file.is_file() and file.parent == ROOT:
                route.fulfill(path=str(file))
            else:
                route.fulfill(status=404, body='not found')
        self.context.route('https://admin.test/admin/**', static_route)
        self.page.goto('https://admin.test/admin/')
        expect(self.page.locator('#environment')).to_have_text('未配置 API')
        expect(self.page.locator('#login-submit')).to_be_disabled()
        expect(self.page.locator('#config-message')).to_contain_text('真实 HTTPS')
        self.assertEqual(self.requests, [])

    def test_modal_keyboard_focus_and_note_validation(self):
        self.login()
        self.open_first()
        self.page.locator('#reject').click()
        expect(self.page.locator('#confirm-cancel')).to_be_focused()
        self.page.locator('#review-note').evaluate("e => {e.value='x'.repeat(501);e.dispatchEvent(new Event('input'));}")
        self.page.locator('#confirm-submit').click()
        expect(self.page.locator('#confirm-message')).to_contain_text('500')
        self.assertEqual(self.calls('rejectStory'), [])
        self.page.locator('#confirm-cancel').click()
        self.page.locator('#reject').click()
        expect(self.page.locator('#review-note')).to_have_value('')
        self.page.keyboard.press('Escape')
        expect(self.page.locator('#confirm-dialog')).not_to_be_visible()
        self.page.keyboard.press('Escape')
        expect(self.page.locator('#detail-dialog')).not_to_be_visible()

    def test_malformed_list_is_error_not_empty_success(self):
        self.login()
        self.items[0]['status'] = 'unexpected'
        self.page.locator('[data-status="all"]').click()
        expect(self.page.locator('#workspace-message')).to_contain_text('响应异常')
        expect(self.page.locator('#story-rows tr')).to_have_count(0)
        expect(self.page.locator('#empty-state')).not_to_be_visible()

    def test_actual_render_mobile_overflow_and_storage(self):
        self.login()
        self.open_first()
        self.page.locator('#approve').click()
        expect(self.page.locator('#confirm-dialog')).to_be_visible()
        self.page.locator('#confirm-cancel').click()
        self.page.locator('#detail-close').click()
        for width in [320, 390, 768, 1024, 1440]:
            self.page.set_viewport_size({'width': width, 'height': 844})
            self.assertLessEqual(self.page.evaluate('document.documentElement.scrollWidth'), width)
        self.assertEqual(self.page.evaluate('localStorage.length + sessionStorage.length'), 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
