package cn.campuslife.assistant.story

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import cn.campuslife.assistant.ui.Ui

/** Local drafts plus explicit, acknowledged CloudBase submissions. No automatic publishing. */
class StorySubmissionActivity : StoryScreen() {
    private class Sending {
        var running=false
        var receipt:StoryReceipt?=null
        var failure:String?=null
        var owner:java.lang.ref.WeakReference<StorySubmissionActivity>?=null
    }
    private lateinit var sending:Sending
    private var back:android.window.OnBackInvokedCallback?=null
    private var draft=StoryDraft()
    private var ready=false
    private var loadingError=false
    private var revision=0
    private var pendingSavedState: Bundle?=null
    private lateinit var form: LinearLayout
    private lateinit var message: TextView
    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var nicknameInput: EditText
    private val saveTask=Runnable { saveDraft(false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sending=(lastNonConfigurationInstance as? Sending) ?: Sending()
        sending.owner=java.lang.ref.WeakReference(this)
        if(android.os.Build.VERSION.SDK_INT>=33) {
            back=android.window.OnBackInvokedCallback {onBackPressed()}.also {onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,it)}
        }
        pendingSavedState=savedInstanceState
        val root=Ui.page(this); heading(root,"随记投稿")
        form=body(); message=status("正在读取草稿…").apply { tag="story_draft_status" }
        form.addView(message); root.addView(Ui.scroll(this,form).apply { tag="story_submission_scroll" },LinearLayout.LayoutParams(-1,0,1f))
        repository.loadState { result -> onUi {
            result.fold({ saved ->
                draft=if (savedInstanceState?.containsKey("story_draft_title")==true) StoryDraft(
                    savedInstanceState.getString("story_draft_title").orEmpty(),
                    StoryCategory.entries.firstOrNull { it.name==savedInstanceState.getString("story_draft_category") },
                    savedInstanceState.getString("story_draft_body").orEmpty(),savedInstanceState.getString("story_draft_nickname").orEmpty(),
                    savedInstanceState.getBoolean("story_draft_rights"),savedInstanceState.getBoolean("story_draft_permission")) else saved.draft
                render()
            },{ loadingError=true; error(message,it) })
        } }
    }
    override fun onRetainNonConfigurationInstance():Any=sending
    @Deprecated("Platform compatibility") override fun onBackPressed() {
        if(sending.running) Ui.toast(this,"正在提交，请稍候") else finish()
    }
    private fun changed(next: StoryDraft) {
        draft=next; revision++;sending.failure=null
        if (ready) { message.text="草稿已修改"; main.removeCallbacks(saveTask); main.postDelayed(saveTask,650) }
    }
    private fun field(value: String,hint: String,max: Int,multiline: Boolean=false,onChange: (String)->Unit): EditText =
        Ui.input(this,value,hint,multiline,onChange).apply {
            // Count Unicode code points rather than UTF-16 units; do not silently cut a pasted story.
            filters=arrayOf(InputFilter { source,start,end,dest,dstart,dend ->
                val proposed=dest.subSequence(0,dstart).toString()+source.subSequence(start,end)+dest.subSequence(dend,dest.length)
                if (start!=end && proposed.codePointCount(0,proposed.length)>max) {
                    Ui.toast(this@StorySubmissionActivity,"最多${max}字，超出部分未写入")
                    dest.subSequence(dstart,dend)
                } else null
            })
            inputType=InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled=false
        }
    private fun render() {
        ready=false; form.removeAllViews()
        if(sending.receipt!=null) {showSuccess();return}
        if(!StoryCloudConfig.configured) form.addView(Ui.text(this,"投稿暂未开放",18f,bold=true).apply { tag="story_submission_availability" })
        val reason=intent.getStringExtra(StorySubmissionLauncher.EXTRA_UNAVAILABLE_REASON)
        val availability=when (reason) {
            StorySubmissionLauncher.BROWSER_UNAVAILABLE -> "在线投稿页未能打开。这里只保存本机草稿，不会发送。"
            StorySubmissionLauncher.INVALID_CONFIGURATION -> "投稿地址配置无效。这里只保存本机草稿，不会发送。"
            else -> "草稿仅保存在本机，不会提交或公开。"
        }
        if(!StoryCloudConfig.configured) {form.addView(Ui.space(this,8)); form.addView(Ui.text(this,availability,14f,Ui.muted))}
        else if(StoryCloudConfig.mock) form.addView(Ui.text(this,"本地Mock测试 · 不会发送正式投稿",13f,Ui.muted).apply {tag="story_mock_notice"})
        fun label(value: String) { form.addView(Ui.space(this,18)); form.addView(Ui.text(this,value,15f,bold=true)); form.addView(Ui.space(this,8)) }
        label("标题（2–30字）")
        titleInput=field(draft.title,"作品标题",30) { changed(draft.copy(title=it)) }.apply { tag="story_draft_title"; contentDescription="作品标题，2至30字" }
        form.addView(titleInput,LinearLayout.LayoutParams(-1,-2))
        label("分类（必选）")
        form.addView(Spinner(this).apply {
            tag="story_draft_category"; contentDescription="作品分类，必选"; minimumHeight=Ui.dp(context,48)
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,listOf("请选择分类")+StoryCategory.entries.map { it.label })
            setSelection(draft.category?.ordinal?.plus(1) ?: 0)
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                    val selected=StoryCategory.entries.getOrNull(position-1)
                    if (selected!=draft.category) changed(draft.copy(category=selected))
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        },LinearLayout.LayoutParams(-1,-2))
        label("正文（100–5000字）")
        val counter=Ui.text(this,"${StoryRules.characterCount(draft.body)}/5000",12f,Ui.muted).apply { tag="story_draft_count" }
        bodyInput=field(draft.body,"写下你的经历、生活片段或随笔",5000,true) {
            changed(draft.copy(body=it)); counter.text="${StoryRules.characterCount(it)}/5000"
        }.apply { tag="story_draft_body"; contentDescription="作品正文，100至5000字"; minLines=8; maxLines=14 }
        form.addView(bodyInput,LinearLayout.LayoutParams(-1,-2)); form.addView(counter)
        label("昵称（选填，留空为匿名）")
        nicknameInput=field(draft.nickname,"匿名",30) { changed(draft.copy(nickname=it)) }.apply { tag="story_draft_nickname"; contentDescription="投稿昵称，可留空" }
        form.addView(nicknameInput,LinearLayout.LayoutParams(-1,-2))
        label("投稿授权")
        form.addView(CheckBox(this).apply {
            text="我确认投稿内容由我原创或已获得合法授权，并同意校伴在审核通过后展示该作品。我保留依法享有的著作权，并可申请撤下作品。"
            textSize=14f;setTextColor(Ui.ink);tag="story_draft_rights";minimumHeight=Ui.dp(context,48)
            isChecked=draft.originalRights && draft.displayPermission
            setOnCheckedChangeListener {_,checked->changed(draft.copy(originalRights=checked,displayPermission=checked))}
        })
        form.addView(Ui.textButton(this,"撤稿与版权反馈") {cn.campuslife.assistant.feedback.FeedbackLauncher.open(this)}.apply {tag="story_copyright"})
        form.addView(Ui.space(this,18))
        message=status(sending.failure.orEmpty()).apply { tag="story_draft_status" }; form.addView(message)
        if(StoryCloudConfig.configured) {
            form.addView(Ui.button(this,if(sending.running)"提交中…" else "提交随记",true) {submitStory()}.apply {tag="story_submit"})
            form.addView(Ui.space(this,8))
        }
        form.addView(Ui.button(this,"校验草稿") {
            val errors=StoryRules.validate(draft)
            titleInput.error=if (StoryDraftError.TITLE in errors) StoryDraftError.TITLE.message else null
            bodyInput.error=if (StoryDraftError.BODY in errors) StoryDraftError.BODY.message else null
            nicknameInput.error=if (StoryDraftError.NICKNAME in errors) StoryDraftError.NICKNAME.message else null
            message.text=if (errors.isEmpty()) "格式已检查，未提交。昵称：${draft.nicknameLabel}" else errors.joinToString("\n") { it.message }
        }.apply { tag="story_draft_validate" })
        form.addView(Ui.space(this,8))
        form.addView(Ui.button(this,"保存本机草稿") { saveDraft(true) }.apply { tag="story_draft_save" })
        form.addView(Ui.space(this,8))
        form.addView(Ui.textButton(this,"清除本机草稿") {
            AlertDialog.Builder(this).setTitle("清除本机草稿？").setMessage("仅清除这份随记草稿，不影响收藏、阅读进度或其他资料。")
                .setNegativeButton("取消",null).setPositiveButton("清除") { _,_ ->
                    main.removeCallbacks(saveTask); draft=StoryDraft(); revision++; render(); saveDraft(true)
                }.show()
        }.apply { tag="story_draft_clear" })
        ready=true
        if(sending.running) {Ui.enabled(form,false);message.text="提交中…"}
    }
    private fun submitStory() {
        if(!ready || sending.running)return
        val errors=StoryRules.validate(draft)
        if(errors.isNotEmpty()) {
            titleInput.error=if(StoryDraftError.TITLE in errors)StoryDraftError.TITLE.message else null
            bodyInput.error=if(StoryDraftError.BODY in errors)StoryDraftError.BODY.message else null
            message.text=errors.filter {it!=StoryDraftError.DISPLAY_PERMISSION}.joinToString("\n") {it.message};return
        }
        main.removeCallbacks(saveTask);sending.running=true;sending.failure=null
        Ui.enabled(form,false);message.text="提交中…"
        val retained=sending;val snapshot=draft
        repository.submit(snapshot) {result->android.os.Handler(android.os.Looper.getMainLooper()).post {
            retained.running=false
            result.fold({retained.receipt=it},{retained.failure=if(it is StoryCloudException)it.message else "未能确认提交结果，草稿已保留。重试将使用同一投稿编号。"})
            retained.owner?.get()?.takeIf {!it.isDestroyed && !it.isFinishing}?.let {activity->
                if(retained.receipt!=null) {activity.main.removeCallbacks(activity.saveTask);activity.ready=false;activity.draft=StoryDraft();activity.showSuccess()}
                else if(activity.ready) {Ui.enabled(activity.form,true);activity.message.text=retained.failure}
            }
        }}
    }
    private fun showSuccess() {
        val receipt=sending.receipt ?: return
        ready=false;form.removeAllViews()
        if(StoryCloudConfig.mock)form.addView(Ui.text(this,"本地Mock测试 · 非正式投稿",13f,Ui.muted))
        form.addView(Ui.space(this,30))
        form.addView(Ui.text(this,if(receipt.status=="pending")"投稿成功，等待审核" else "这份投稿已接收",23f,bold=true).apply {tag="story_submission_success"})
        form.addView(Ui.space(this,18));form.addView(Ui.text(this,"投稿编号：${receipt.submissionId}",13f,Ui.muted))
        form.addView(Ui.space(this,24));form.addView(Ui.button(this,"返回随记",true) {
            startActivity(android.content.Intent(this,StoryActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));finish()
        }.apply {tag="story_submission_done"})
    }
    private fun saveDraft(explicit: Boolean) {
        if (!ready || loadingError || sending.running || sending.receipt!=null) return
        main.removeCallbacks(saveTask)
        val snapshot=draft; val request=revision
        repository.change({ old -> old.copy(draft=snapshot) }) { result -> onUi {
            if (request==revision && !sending.running && sending.receipt==null) result.fold({ message.text=if (explicit) "已保存到本机，未提交。" else "草稿已保存到本机" },{ error(message,it) })
        } }
    }
    override fun onPause() { main.removeCallbacks(saveTask); saveDraft(false); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (!ready) pendingSavedState?.let(outState::putAll)
        if (ready) {
            outState.putString("story_draft_title",draft.title); outState.putString("story_draft_category",draft.category?.name)
            outState.putString("story_draft_body",draft.body); outState.putString("story_draft_nickname",draft.nickname)
            outState.putBoolean("story_draft_rights",draft.originalRights); outState.putBoolean("story_draft_permission",draft.displayPermission)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        main.removeCallbacks(saveTask)
        if(sending.owner?.get()===this)sending.owner=null
        if(android.os.Build.VERSION.SDK_INT>=33)back?.let {onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)}
        super.onDestroy()
    }
}
