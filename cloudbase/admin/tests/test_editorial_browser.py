"""Edge + actual loopback shared service; temp fixture DB, no cloud/real config."""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification' / 'suiji-admin-20261111'


class EditorialBrowserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='suiji-browser-')
        script = "require('./mock/server').startMock({port:0,file:process.argv[1]}).then(a=>console.log(a.url))"
        cls.proc = subprocess.Popen(['node', '-e', script, str(Path(cls.temp.name) / 'db.json')], cwd=ROOT,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        cls.base = cls.proc.stdout.readline().strip()
        assert cls.base.startswith('http://127.0.0.1:')
        cls.token = cls.call('adminLogin', {'username': 'admin', 'password': 'mock-admin-password'})[1]['data']['accessToken']
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(channel='msedge', headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close(); cls.playwright.stop()
        cls.proc.terminate(); cls.proc.wait(timeout=10)
        cls.proc.stdout.close(); cls.proc.stderr.close()
        cls.temp.cleanup()

    @classmethod
    def call(cls, action, payload=None, authenticated=False):
        headers = {'Content-Type': 'application/json'}
        if authenticated:
            headers['Authorization'] = 'Bearer ' + cls.token
        req = Request(cls.base + '/api', data=json.dumps({'action': action, 'payload': payload or {}}).encode(), headers=headers)
        try:
            with urlopen(req, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def setUp(self):
        self.context = self.browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        self.external = []
        def guard(route):
            if route.request.url.startswith(self.base + '/'):
                route.continue_()
            else:
                self.external.append(route.request.url); route.abort()
        self.context.route('**/*', guard)
        self.page = self.context.new_page(); self.page.set_default_timeout(5000)
        self.errors = []; self.page.on('pageerror', lambda error: self.errors.append(str(error)))
        self.page.goto(self.base + '/admin/')
        self.page.locator('#username').fill('admin'); self.page.locator('#password').fill('mock-admin-password')
        self.page.locator('#login-submit').click(); expect(self.page.locator('#workspace')).to_be_visible()
        expect(self.page.locator('#refresh')).to_be_enabled()

    def tearDown(self):
        self.assertEqual(self.external, []); self.assertEqual(self.errors, [])
        self.context.close()

    def draft(self, title):
        p = self.page; p.locator('#create-editorial').click()
        p.locator('#edit-title').fill(title)
        p.locator('#edit-sourceType').select_option('douyin_comment')
        p.locator('#edit-sourceUrl').fill('https://www.douyin.com/video/123456789')
        p.locator('#edit-publisherName').fill('作品发布者')
        p.locator('#edit-authorName').fill('评论作者')
        p.locator('#edit-sourceWorkTitle').fill('来源作品')
        p.locator('#edit-commentLocator').fill('置顶评论')
        p.locator('#edit-content').fill('完整管理员草稿。' * 40)
        p.locator('#edit-summary').fill('独立介绍，不自动截取全文。')
        p.locator('#edit-rightsNote').fill('仅管理员可读的授权凭据')
        p.locator('#edit-editorialRank').fill('-5')
        p.locator('#save').click()
        expect(p.locator('#detail-title')).to_have_text(title)
        expect(p.locator('#save')).to_be_enabled()
        return self.call('getPendingStories', {'status': 'all'}, True)[1]['data']['items'][0]['id']

    def confirm(self):
        expect(self.page.locator('#confirm-dialog')).to_be_visible()
        self.page.locator('#confirm-submit').click()
        expect(self.page.locator('#confirm-dialog')).not_to_be_visible()
        expect(self.page.locator('#save')).to_be_enabled()

    def test_editorial_publish_edit_revoke_hide_withdraw_and_privacy(self):
        p = self.page; sid = self.draft('离线精选流程')
        p.screenshot(path=str(OUT / 'editorial-draft-desktop.png'), full_page=True)
        p.locator('#edit-sourceType').scroll_into_view_if_needed()
        p.screenshot(path=str(OUT / 'editorial-form-desktop.png'), full_page=True)
        self.assertEqual(self.call('getPublishedStories')[1]['data']['items'], [])
        p.locator('#approve').click(); p.locator('#confirm-cancel').click()
        self.assertEqual(self.call('getPublishedStories')[1]['data']['items'], [])
        p.locator('#approve').click(); self.confirm()
        public_id = self.call('getSubmissionDetail', {'id': sid}, True)[1]['data']['publishedStoryId']
        self.assertEqual(self.call('getStoryDetail', {'id': public_id})[1]['data']['content'], '')
        p.locator('[data-status=approved]').click(); expect(p.locator('#refresh')).to_be_enabled()
        p.get_by_role('button', name='查看投稿：离线精选流程', exact=True).click()
        expect(p.locator('#managed-editor')).to_be_visible()
        p.locator('#edit-rightsStatus').select_option('authorized'); p.locator('#save').click()
        p.screenshot(path=str(OUT / 'editorial-public-confirm.png'), full_page=True)
        self.confirm()
        self.assertTrue(self.call('getStoryDetail', {'id': public_id})[1]['data']['content'])
        p.locator('#edit-rightsStatus').select_option('revoked'); p.locator('#save').click(); self.confirm()
        d = self.call('getStoryDetail', {'id': public_id})[1]['data']
        self.assertEqual(d['content'], ''); self.assertNotIn('rightsNote', d)
        self.assertEqual(d['publisherName'], '作品发布者'); self.assertEqual(d['authorName'], '评论作者')
        p.locator('#edit-hidden').check(); p.locator('#save').click(); self.confirm()
        self.assertEqual(self.call('getStoryDetail', {'id': public_id})[0], 404)
        p.locator('#edit-hidden').uncheck(); p.locator('#save').click(); self.confirm()
        self.assertEqual(self.call('getStoryDetail', {'id': public_id})[0], 200)
        p.locator('#withdraw').click(); self.confirm()
        self.assertEqual(self.call('getStoryDetail', {'id': public_id})[0], 404)
        self.assertEqual(self.call('getPublishedStories')[1]['data']['items'], [])

    def test_mobile_unicode_validation_unsaved_publication_and_session_clear(self):
        p = self.page; p.set_viewport_size({'width': 390, 'height': 844})
        sid = self.draft('离线移动端随记')
        p.locator('#edit-excerpt').fill('😀' * 81); p.locator('#save').click()
        expect(p.locator('#editor-message')).to_contain_text('摘录最多80')
        p.locator('#edit-excerpt').fill('😀' * 80); p.locator('#approve').click()
        expect(p.locator('#editor-message')).to_contain_text('先保存修改')
        p.locator('#save').click(); expect(p.locator('#save')).to_be_enabled()
        expect(p.locator('#edit-excerpt')).to_have_value('😀' * 80)
        p.screenshot(path=str(OUT / 'editorial-mobile.png'), full_page=True)
        p.locator('#edit-sourceType').scroll_into_view_if_needed()
        p.screenshot(path=str(OUT / 'editorial-form-mobile.png'), full_page=True)
        self.assertFalse(p.evaluate('document.documentElement.scrollWidth > innerWidth'))
        p.locator('#detail-close').click(); p.locator('#logout').click()
        expect(p.locator('#edit-content')).to_have_value(''); expect(p.locator('#edit-rightsNote')).to_have_value('')
        self.assertEqual(self.call('getSubmissionDetail', {'id': sid}, True)[1]['data']['excerpt'], '😀' * 80)


if __name__ == '__main__':
    unittest.main(verbosity=2)
