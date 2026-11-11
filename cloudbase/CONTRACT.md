# Story API contract v1 (2026-11-10)

POST to configured fixed HTTPS API endpoint, JSON `{action, payload}`. Bearer access token in Authorization when required. Response `{ok:true,data:{...}}` or `{ok:false,error:{code,message}}` with appropriate HTTP status; <=256KiB. No database/admin credentials in app/admin bundle.

Auth: anonymous login directly via configured CloudBase auth origin `POST /auth/v1/signin/anonymously`, header x-device-id=random persisted UUID (not hardware ID), body {}. Returns access_token/expires_in/sub, tokens kept in process memory. Server verifies bearer through fixed auth origin GET /auth/v1/user/me and uses ONLY validated sub as UID, never client claimed uid. adminLogin proxies official POST /auth/v1/signin with username/password and server-verifies UID allowlist; returns {accessToken,expiresIn}. Other admin calls reverify UID each time. Mock serves /auth/v1/signin/anonymously and own test login; never active in production entry.

All categories: LIFE,GROWTH,WARMTH,TWIST,OTHER. consentVersion=2026-11-10-v1.

submitStory payload {submissionId:UUID,title,category,content,authorName,consent:true,consentVersion}. Server stamps consentAt/createdAt. data {submissionId,id,status:"pending"|"approved"|"rejected",duplicate:boolean}. Unique global submissionId; exact replay same owner+content idempotent; changed content/owner rejects 409. Auth required. Default 1/min +5/day per UID and global 50 new/day, transaction atomic; replay before rate charging. Never accept supplied status/reviewer/uid.
getPublishedStories payload {cursor?:string,limit?:20,category?:enum}. data {items:[{id,title,category,summary,authorName,publishedAt,sourceType:"submission"}],nextCursor:string|null}. Public, metadata only, stable cursor order. Published ids must match story-cloud-[a-z0-9-]+ <=75 chars to fit existing stable IDs.
getStoryDetail payload {id}. data {id,title,category,content,authorName,publishedAt,sourceType:"submission"}. Public, 404 STORY_NOT_FOUND for withdrawn/unpublished. No submission metadata/UID/reviewerNote.

Admin:
adminLogin payload {username,password}, result above.
getPendingStories payload {status:"all"|"pending"|"approved"|"rejected",cursor?,limit:20}. data {items:[{id,title,category,authorName,status,createdAt,published:boolean}],nextCursor,counts:{pending,approved,rejected}}.
getSubmissionDetail payload {id}. data {id,title,category,content,authorName,status,createdAt,updatedAt,consentAt,consentVersion,reviewerNote,reviewedAt,published:boolean,publishedStoryId?:string}.
approveStory payload {id}; data {id,status:"approved",publishedStoryId}; atomic publish + update + audit, repeat approval returns same publication, rejected/withdrawn not republished.
rejectStory payload {id,note?:string<=500}; data {id,status:"rejected"}; pending only, idempotent rejection.
deletePublishedStory payload {id,note?:string<=500}, id refers submission; data {id,withdrawn:true}; atomic delete publication + mark withdrawn + audit. approved status retained with published=false. Cannot republish withdrawn submission.

Server persistence: story_submissions, published_stories, story_rate_limits, story_audit_log, story_stats. Database client direct access deny all; node-sdk server ambient identity only. Transaction document reads/writes, no full DB scans for counts; counter doc. Mock same service logic with file-backed test repository and strict loopback bind. No external deployment performed.

Android: empty config defaults to local drafts; CloudBase URLs build configuration (not secrets). Mock only via explicit debug build option; release never accepts cleartext/mock. Local originals remain unchanged. Cloud metadata from pages; read body cache max100 with AtomicJsonFile. Authoritative 404 removes cached story; no network falls back to offline cached body/local library. Online successful refresh lists fetched pages + local, not old cached revoked rows. Never delete cache because an ID is merely absent from one page.
