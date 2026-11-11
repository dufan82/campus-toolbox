"""Only against the real loopback Mock; no injected API and no cloud requests.
Read-only review: confirmation is opened then cancelled; no approve/reject/withdraw.
"""
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from uuid import uuid4
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / 'tests' / 'artifacts'
BASE = 'http://127.0.0.1:8787'


def post(action, payload, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(BASE + '/api', data=json.dumps({'action': action, 'payload': payload}).encode(), headers=headers)
    try:
        with urlopen(req, timeout=10) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.load(error)


def run():
    assert urlparse(BASE).hostname == '127.0.0.1'
    anon_request = Request(BASE + '/auth/v1/signin/anonymously', data=b'{}', headers={
        'Content-Type': 'application/json', 'x-device-id': str(uuid4())
    })
    with urlopen(anon_request, timeout=10) as response:
        anonymous_token = json.load(response)['access_token']
    checks = []
    # Bypasses the UI entirely: the server, not hidden buttons, must deny these.
    for action, payload in [('getPendingStories', {'status': 'all', 'limit': 20}),
                            ('getSubmissionDetail', {'id': 'submission-11111111-1111-4111-8111-111111111111'}),
                            ('approveStory', {'id': 'submission-11111111-1111-4111-8111-111111111111'}),
                            ('rejectStory', {'id': 'submission-11111111-1111-4111-8111-111111111111'}),
                            ('deletePublishedStory', {'id': 'submission-11111111-1111-4111-8111-111111111111'})]:
        for credential_case, token in [('missing', None), ('invalid', 'invalid-test-token'), ('authenticated-non-admin', anonymous_token)]:
            status, data = post(action, payload, token)
            assert status in (401, 403) and data['ok'] is False, 'Server authentication must fail closed'
            if token == anonymous_token:
                assert status == 403, 'Authenticated non-admin UID must be denied server-side'
            checks.append({'action': action, 'payload': payload, 'credentialCase': credential_case,
                           'status': status, 'response': data})
    anonymous_token = ''

    ARTIFACTS.mkdir(exist_ok=True)
    files, errors = [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN')
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        # Deliberately no request interception or credential tracing.
        page.goto(BASE + '/admin/')
        expect(page.locator('#environment')).to_have_text('本地Mock')

        def capture(name):
            file = ARTIFACTS / name
            page.screenshot(path=str(file), full_page=True)
            files.append(file.name)

        capture('mock-login-desktop.png')
        # Public fixture credentials agreed for this loopback Mock only.
        page.locator('#username').fill('admin')
        page.locator('#password').fill('mock-admin-password')
        page.locator('#login-submit').click()
        expect(page.locator('#workspace')).to_be_visible()
        expect(page.locator('#count-pending')).not_to_have_text('—')
        capture('mock-list-desktop.png')
        detail_verified = False
        if page.locator('#story-rows button').count():
            page.locator('#story-rows button').first.click()
            expect(page.locator('#detail-body')).to_be_visible()
            capture('mock-smoke-detail-desktop.png')
            if page.locator('#approve').is_visible():
                page.locator('#approve').click()
                expect(page.locator('#confirm-dialog')).to_be_visible()
                capture('mock-smoke-confirm-desktop.png')
                page.locator('#confirm-cancel').click()
            page.locator('#detail-close').click()
            detail_verified = True
        page.set_viewport_size({'width': 390, 'height': 844})
        capture('mock-list-mobile.png')
        assert page.evaluate('document.documentElement.scrollWidth') <= 390
        assert page.evaluate('localStorage.length + sessionStorage.length') == 0
        page.locator('#logout').click()
        expect(page.locator('#login-view')).to_be_visible()
        assert not errors, 'Unexpected browser script error'
        browser.close()
    report = {'generated': datetime.now(timezone.utc).isoformat(),
              'scope': 'real local Mock only; no cloud deployment', 'url': BASE + '/admin/',
              'server_denial_checks': 15, 'detail_verified': detail_verified,
              'screenshots': files, 'browser_errors': len(errors), 'ui_mutation_requests': 0,
              'server_denied_mutation_requests': 9, 'checks': checks}
    (ARTIFACTS / 'mock-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'checks'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    run()
