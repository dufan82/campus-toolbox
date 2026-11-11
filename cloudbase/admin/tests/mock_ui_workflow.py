"""Explicitly opt-in real loopback fixture mutations, authorized for local Mock only.
Creates three uniquely marked test submissions via API, then uses real Edge UI to
approve, reject and withdraw; retains one pending fixture for manual review.
"""
import argparse
from datetime import datetime, timezone
import json
from uuid import uuid4
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright, expect
from smoke_mock import ARTIFACTS, BASE, post


def run():
    parser = argparse.ArgumentParser()
    parser.add_argument('--allow-local-fixture-writes', action='store_true')
    args = parser.parse_args()
    if not args.allow_local_fixture_writes:
        parser.error('requires --allow-local-fixture-writes; writes only to fixed loopback Mock')
    assert BASE == 'http://127.0.0.1:8787'
    fixtures = []
    suffix = uuid4().hex[:6]
    paragraph = ('这是校伴本机 Mock 的独立界面验收稿件，不是真实用户投稿，也不用于云端发布。'
                 '傍晚的校园图书馆安静下来，窗边有人把借来的书放回书架，给下一位读者留好位置。'
                 '我们记录一件普通的小事，确认故事正文在审核详情中完整保留，且按照纯文本而非 HTML 展示。'
                 '这段文字只用于本地通过、内部拒绝与撤下流程测试，原有用户内容不作更改。\n\n')
    content = (paragraph * 5)[:499] + '。'
    assert len(content) == 500
    for label in ['通过撤下', '内部拒绝', '保留待审']:
        request = Request(BASE + '/auth/v1/signin/anonymously', data=b'{}', headers={
            'Content-Type': 'application/json', 'x-device-id': str(uuid4())
        })
        with urlopen(request, timeout=10) as response:
            token = json.load(response)['access_token']
        payload = {'submissionId': str(uuid4()), 'title': f'本机验收·{label}·{suffix}', 'category': 'WARMTH',
                   'authorName': '本机测试', 'content': content,
                   'consent': True, 'consentVersion': '2026-11-10-v1'}
        status, result = post('submitStory', payload, token)
        token = ''
        assert status == 200 and result['ok'], 'Local fixture submit failed; no credentials logged'
        fixtures.append({'label': label, 'id': result['data']['id'], 'title': payload['title'],
                         'submitPayload': payload, 'submitResponse': result})
    ARTIFACTS.mkdir(exist_ok=True)
    screenshots, mutations, errors = [], [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        page = browser.new_page(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        page.set_default_timeout(7000)
        page.on('pageerror', lambda error: errors.append(str(error)))

        def observe(response):
            if response.url != BASE + '/api' or response.request.method != 'POST':
                return
            body = response.request.post_data_json
            if body['action'] in ['approveStory', 'rejectStory', 'deletePublishedStory']:
                mutations.append({'action': body['action'], 'payload': body['payload'],
                                  'status': response.status, 'response': response.json()})
        page.on('response', observe)

        def capture(name):
            page.screenshot(path=str(ARTIFACTS / name), full_page=True)
            screenshots.append(name)

        def open_fixture(fixture):
            page.get_by_role('button', name='查看投稿：' + fixture['title'], exact=True).click()
            expect(page.locator('#detail-body')).to_be_visible()
            expect(page.locator('#detail-meta')).to_contain_text(fixture['id'])
            expect(page.locator('#detail-dialog [data-mock-badge]')).to_be_visible()

        page.goto(BASE + '/admin/')
        expect(page.locator('#environment')).to_have_text('本地Mock')
        page.locator('#username').fill('admin')
        page.locator('#password').fill('mock-admin-password')
        page.locator('#login-submit').click()
        expect(page.locator('#count-pending')).not_to_have_text('—')
        initial_pending = int(page.locator('#count-pending').inner_text())
        assert initial_pending >= 3
        capture('mock-populated-list-desktop.png')

        open_fixture(fixtures[0])
        capture('mock-detail-desktop.png')
        page.locator('#approve').click()
        expect(page.locator('#confirm-dialog')).to_be_visible()
        expect(page.locator('#confirm-dialog [data-mock-badge]')).to_be_visible()
        assert len(mutations) == 0, 'Opening confirmation must not approve'
        capture('mock-confirm-desktop.png')
        page.locator('#confirm-cancel').click()
        assert len(mutations) == 0
        page.locator('#approve').click()
        page.locator('#confirm-submit').click()
        expect(page.locator('#count-pending')).to_have_text(str(initial_pending - 1))
        assert len(mutations) == 1 and mutations[0]['status'] == 200
        public_id = mutations[0]['response']['data']['publishedStoryId']
        status, public = post('getStoryDetail', {'id': public_id})
        assert status == 200 and public['ok']
        assert public['data']['title'] == fixtures[0]['title']
        assert not any(key in public['data'] for key in ['reviewerNote', 'ownerUid', 'reviewerUid', 'consentAt'])

        page.locator('[data-status="approved"]').click()
        open_fixture(fixtures[0])
        page.locator('#withdraw').click()
        expect(page.locator('#confirm-description')).to_contain_text('不能重新发布')
        capture('mock-withdraw-confirm-desktop.png')
        page.locator('#review-note').fill('本机UI验收撤下，非生产。')
        page.locator('#confirm-submit').click()
        expect(page.locator('#detail-dialog')).not_to_be_visible()
        expect(page.locator('#workspace-message')).to_contain_text('已撤下')
        assert len(mutations) == 2 and mutations[1]['payload']['id'] == fixtures[0]['id']
        status, withdrawn = post('getStoryDetail', {'id': public_id})
        assert status == 404 and withdrawn['error']['code'] == 'STORY_NOT_FOUND'
        open_fixture(fixtures[0])
        expect(page.locator('#detail-meta')).to_contain_text('已撤下')
        expect(page.locator('#approve')).not_to_be_visible()
        expect(page.locator('#withdraw')).not_to_be_visible()
        capture('mock-withdrawn-detail-desktop.png')
        page.locator('#reviewer-note').scroll_into_view_if_needed()
        capture('mock-withdrawn-note-desktop.png')
        page.locator('#detail-close').click()

        page.locator('[data-status="pending"]').click()
        open_fixture(fixtures[1])
        page.locator('#reject').click()
        internal_note = '本机UI验收内部备注，仅后台可见。<b>不是HTML</b>'
        page.locator('#review-note').fill(internal_note)
        capture('mock-reject-confirm-desktop.png')
        page.locator('#confirm-submit').click()
        expect(page.locator('#count-pending')).to_have_text(str(initial_pending - 2))
        assert len(mutations) == 3 and mutations[2]['payload']['note'] == internal_note
        page.locator('[data-status="rejected"]').click()
        open_fixture(fixtures[1])
        expect(page.locator('#reviewer-note')).to_have_text(internal_note)
        assert page.locator('#reviewer-note b').count() == 0
        expect(page.locator('#approve')).not_to_be_visible()
        capture('mock-rejected-detail-desktop.png')
        page.locator('#reviewer-note').scroll_into_view_if_needed()
        capture('mock-rejected-note-desktop.png')
        page.locator('#detail-close').click()
        page.locator('[data-status="pending"]').click()
        open_fixture(fixtures[2])
        expect(page.locator('#approve')).to_be_visible()
        page.locator('#detail-close').click()
        page.set_viewport_size({'width': 390, 'height': 844})
        capture('mock-populated-list-mobile.png')
        assert page.evaluate('document.documentElement.scrollWidth') <= 390
        assert page.evaluate('localStorage.length + sessionStorage.length') == 0
        page.locator('#logout').click()
        expect(page.locator('#login-view')).to_be_visible()
        assert errors == []
        assert [m['action'] for m in mutations] == ['approveStory', 'deletePublishedStory', 'rejectStory']
        assert all(m['status'] == 200 and m['response']['ok'] for m in mutations)
        browser.close()
    report = {'generated': datetime.now(timezone.utc).isoformat(), 'scope': 'real loopback Mock fixture mutations only; no cloud',
              'url': BASE + '/admin/', 'fixtures': fixtures, 'uiMutations': mutations,
              'publicDetailBeforeWithdraw': public, 'publicDetailAfterWithdraw': withdrawn,
              'pendingFixtureRetained': fixtures[2]['id'], 'browserErrors': len(errors), 'screenshots': screenshots}
    (ARTIFACTS / 'mock-ui-workflow.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ['fixtures', 'uiMutations', 'publicDetailBeforeWithdraw', 'publicDetailAfterWithdraw']}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    run()
