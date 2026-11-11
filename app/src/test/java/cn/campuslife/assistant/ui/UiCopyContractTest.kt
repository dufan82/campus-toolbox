package cn.campuslife.assistant.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-only UI regression. Reads versioned Kotlin, never config, credentials or user records. */
class UiCopyContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java/cn/campuslife/assistant").isDirectory }

    private fun source(path: String) = File(root, "app/src/main/java/cn/campuslife/assistant/$path").readText(Charsets.UTF_8)
    private fun has(path: String, vararg values: String) = values.forEach { assertTrue("$path: $it", source(path).contains(it)) }
    private fun lacks(path: String, vararg values: String) = values.forEach { assertFalse("$path: $it", source(path).contains(it)) }

    @Test fun watermarksKeepActionsAndNecessarySharingHints() {
        has("ui/ProfileActivity.kt", "学号仅在勾选后显示。", "资料保存失败，请重试")
        lacks("ui/ProfileActivity.kt", "已有选择不变", "资料只保存在本机，可随时修改")
        has("ui/WatermarkActivity.kt", "已保存到相册“校园生活助手”", "文件访问或保存权限不足")
        lacks("ui/WatermarkActivity.kt", "沿用原", "原图未修改")
        has("ui/ExportDialog.kt", "另存新图，不覆盖原图。", "学号需手动选择。", "分享时可见。")
        lacks("ui/StylePanel.kt", "保证完整显示", "按原照片方向和尺寸生成新图片")
    }

    @Test fun normalPagesOmitDeveloperNarrationAndSlogans() {
        has("planner/PlannerActivity.kt", "暂无倒数日")
        lacks("planner/PlannerActivity.kt", "记住重要的日子，不用一直惦记", "日期支持点选")
        lacks("map/CampusMapActivity.kt", "local.properties", "重新构建", "虚假的定位图", "不会自动重复请求")
        lacks("batch/BatchWatermarkActivity.kt", "不一次加载整批原图", "预览暂时释放以留出内存", "更改仅用于批量模块")
        lacks("update/UpdateActivity.kt", "本仓库的正式Release", "取消不影响其他功能")
        has("gate/GateActivity.kt", "将入口发给微信文件传输助手，再点开链接。")
        has("feedback/FeedbackActivity.kt", "格式正确，尚未提交。", "此页不会提交；联系方式可留空。")
    }

    @Test fun realFailureReasonsAndDestructiveConfirmationsRemain() {
        has("ui/WatermarkActivity.kt", "本机可用内存不足", "文件访问或保存权限不足")
        has("map/CampusMapActivity.kt", "查询超时，请检查网络后重试。", "未授予定位，仍可搜索和浏览校园地点。")
        has("schedule/ScheduleActivity.kt", "确认删除这条课程记录？", "与以下课程有重叠")
        has("planner/PlannerActivity.kt", "删除后同时取消它的提醒。", "通知权限未授予")
        has("update/UpdateActivity.kt", "请允许校伴安装应用，以继续更新。", "安装包未通过校验，请重新下载")
        has("express/ExpressActivity.kt", "可发起跳转不代表已到达取件页")
    }

    @Test fun schoolHistoryDiffAndReplacementWarningsAreNotErased() {
        has("schedule/ScheduleTransferActivity.kt", "最近检查", "record.result", "record.details", "查看更新内容",
            "schedule.diff.school", "schedule.diff.local", "更新前", "更新后", "SchoolReadFailure.message",
            "恢复后会替换当前课表，包括手动课程和历史记录。", "备份中没有课程，恢复会清空当前课表。")
        has("schedule/DailySyncSettingsActivity.kt", "07:00", "本机加密保存课表凭据", "不保存密码", "清除凭据", "最近检查")
    }

    @Test fun mapFirstConsentAndLocationDisclosureRemainExplicit() {
        has("map/CampusMapActivity.kt", "运行必要的设备/网络信息由高德处理", "启用定位后，高德还会处理位置信息",
            "只有点击“我的位置”后才申请系统定位权限", "不记录行走轨迹", "查看高德隐私政策", "查看高德服务协议", "同意并使用地图", "暂不使用地图")
        has("map/CampusReferenceView.kt", "不是实时定位图")
        has("map/CampusReferenceDialog.kt", "原图“当前位置”是标牌标记，不是实时定位。", "ref_notice")
        has("map/CampusPlaceEditor.kt", "只记录公共地点，不填姓名、电话等个人资料。", "名称最多40字；保存后在地图上长按标注位置。")
    }
}
