/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package io.github.grahamzen.izuko.installer

import android.R.attr.progressBarStyleHorizontal
import android.R.attr.state_focused
import android.R.attr.state_pressed
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * 唯一的界面: 设备信息、当前进度、出错原因与几个按钮. 原生控件、代码里排版 (不引 AndroidX),
 * 遥控器上下左右就能在按钮间移动.
 */
class InstallerActivity : Activity() {
    private lateinit var deviceLine: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var detail: TextView
    private lateinit var buttons: ButtonFlow
    private lateinit var report: LinearLayout
    private lateinit var reportLeft: TextView
    private lateinit var reportRight: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        InstallFlow.start(this)
    }

    override fun onStart() {
        super.onStart()
        InstallFlow.listener = ::render
    }

    override fun onResume() {
        super.onResume()
        InstallFlow.recheckPermission(this)
    }

    override fun onStop() {
        InstallFlow.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        uploadDialog?.dismiss()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val padding = (resources.displayMetrics.widthPixels * 0.1).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, dp(32), padding, dp(32))
        }
        root.addView(text(32f, Color.WHITE).apply { setText(R.string.title) })
        deviceLine = text(15f, MUTED).apply {
            text = getString(
                R.string.device_info,
                AndroidVersions.name(Build.VERSION.SDK_INT, Build.VERSION.RELEASE),
                Build.VERSION.SDK_INT,
                deviceAbis().joinToString(", "),
            )
        }
        root.addView(deviceLine, margins(top = 8))
        status = text(22f, Color.WHITE)
        root.addView(status, margins(top = 32))
        hint = text(17f, MUTED)
        root.addView(hint, margins(top = 8))
        progress = ProgressBar(this, null, progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress, margins(top = 24, height = dp(8)))
        progressText = text(15f, MUTED)
        root.addView(progressText, margins(top = 8))
        detail = text(13f, MUTED)
        root.addView(detail, margins(top = 16))
        // 放不下就换行 (见 ButtonFlow)
        buttons = ButtonFlow(this, dp(16), dp(12))
        root.addView(buttons, margins(top = 32))
        // 诊断信息: 失败时才显示, 两栏排开, 一屏拍得下
        report = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(text(15f, Color.WHITE).apply { setText(R.string.report_title) })
            val columns = LinearLayout(this@InstallerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            reportLeft = text(12f, MUTED)
            reportRight = text(12f, MUTED)
            columns.addView(reportLeft, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            columns.addView(
                reportRight,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(24) },
            )
            addView(columns, margins(top = 8))
        }
        root.addView(report, margins(top = 24))
        root.addView(
            text(13f, MUTED).apply {
                text = getString(R.string.footer, BuildConfig.VERSION_NAME, BuildConfig.REPOSITORY)
            },
            margins(top = 40),
        )
        // 失败时内容可能超过一屏 (诊断信息), 放进可滚动的容器; 内容不满一屏时照样垂直居中
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(0x12, 0x14, 0x16))
            // 右边一栏交流群 (见 ChatPanel), 没配群时只有这一栏
            addView(attachChatPanel(this@InstallerActivity, root, MUTED), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun render(state: InstallFlow.State) {
        hint.text = ""
        detail.text = ""
        progressText.text = ""
        progress.visibility = View.GONE
        val failed = state is InstallFlow.State.Failed || state is InstallFlow.State.TooOld ||
            state is InstallFlow.State.UnsupportedAbi || (state is InstallFlow.State.SystemInstaller && state.closed)
        if (failed) showReport() else report.visibility = View.GONE
        // 诊断信息里有同样的系统与架构, 失败时顶上这行省掉
        deviceLine.visibility = if (failed) View.GONE else View.VISIBLE
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        when (state) {
            is InstallFlow.State.TooOld -> {
                status.text = getString(
                    R.string.too_old,
                    AndroidVersions.name(state.sdk, Build.VERSION.RELEASE),
                    state.sdk,
                    AndroidVersions.name(state.minSdk, ""),
                    state.minSdk,
                )
                actions += R.string.button_exit to { finish() }
            }

            is InstallFlow.State.UnsupportedAbi -> {
                status.text = getString(R.string.unsupported_abi, state.abis.joinToString(", "))
                actions += R.string.button_exit to { finish() }
            }

            is InstallFlow.State.NeedsPermission -> {
                status.setText(R.string.permission_title)
                hint.setText(R.string.permission_hint)
                val goToSettings = R.string.button_allow_unknown_sources to ::openUnknownSourcesSettings
                val skip = R.string.button_continue to { InstallFlow.continueWithoutPermission(this) }
                if (state.stillMissing) {
                    detail.setText(
                        if (state.settingsUnavailable) R.string.permission_settings_unavailable else R.string.permission_still_missing,
                    )
                    actions += skip
                    actions += goToSettings
                } else {
                    actions += goToSettings
                    actions += skip
                }
                actions += channelButton()
            }

            InstallFlow.State.ChooseVersion -> {
                status.setText(R.string.choose_title)
                val versions = InstallFlow.channelVersions
                hint.text = listOf(
                    getString(R.string.choose_line, getString(R.string.channel_stable, versions[ReleaseChannel.STABLE].orEmpty()).trim(), getString(R.string.channel_stable_desc)),
                    getString(R.string.choose_line, getString(R.string.channel_test, versions[ReleaseChannel.TEST].orEmpty()).trim(), getString(R.string.channel_test_desc)),
                ).joinToString("\n")
                showPreviousAttempt()
                actions += R.string.button_install_stable to { InstallFlow.chooseChannel(this, ReleaseChannel.STABLE) }
                actions += R.string.button_install_test to { InstallFlow.chooseChannel(this, ReleaseChannel.TEST) }
                actions += R.string.button_upload_from_phone to ::showUploadDialog
            }

            InstallFlow.State.Checking -> {
                status.setText(R.string.checking)
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                showPreviousAttempt()
                // 查版本要连 GitHub, 连不上时要等好一阵: 这时就可以改用手机传
                actions += R.string.button_upload_from_phone to ::showUploadDialog
                actions += channelButton()
            }

            is InstallFlow.State.Downloading -> {
                status.text = getString(R.string.downloading, state.version)
                hint.text = state.fileName
                progress.visibility = View.VISIBLE
                val p = state.progress
                val total = p?.total
                progress.isIndeterminate = state.probing || p == null || total == null || total <= 0
                if (state.probing) {
                    progressText.setText(R.string.probing)
                } else if (p != null) {
                    if (total != null && total > 0) progress.progress = (p.done * 1000 / total).toInt()
                    progressText.text = if (total != null && total > 0) {
                        getString(R.string.progress, bytes(p.done), bytes(total), bytes(p.bytesPerSecond), p.host)
                    } else {
                        getString(R.string.progress_unknown_total, bytes(p.done), bytes(p.bytesPerSecond), p.host)
                    }
                }
                showPreviousAttempt()
                actions += R.string.button_lines to ::showLinesDialog
                actions += R.string.button_upload_from_phone to ::showUploadDialog
                actions += channelButton()
            }

            is InstallFlow.State.ReadyToInstall -> {
                status.text = getString(R.string.ready_title, state.version)
                hint.setText(R.string.ready_hint)
                showPreviousAttempt()
                actions += R.string.button_install to { InstallFlow.installDownloaded(this) }
                // 系统安装界面在部分电视上装到一半就没了 (TCL 上连同助手一起消失): 留一条不依赖它的路
                actions += R.string.button_install_directly to ::confirmSessionInstall
                actions += channelButton()
            }

            is InstallFlow.State.SystemInstaller -> {
                if (state.closed) {
                    status.setText(R.string.system_installer_closed)
                    hint.setText(R.string.system_installer_closed_hint)
                } else {
                    status.text = getString(R.string.installing, state.version)
                    hint.setText(R.string.system_installer_hint)
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = true
                    if (InstallFlow.takeSystemInstallerLaunch()) launchSystemInstaller(state.version, state.apk)
                }
                // 安装界面没弹出来或被关掉时也有路可走; 系统默认选中「取消」, 误按的最多, 所以重新打开排第一
                actions += R.string.button_reopen_system_installer to { InstallFlow.useSystemInstaller() }
                actions += R.string.button_install_directly to ::confirmSessionInstall
                if (state.closed) actions += R.string.button_exit to { finish() }
            }

            is InstallFlow.State.Installing -> {
                status.text = getString(R.string.installing, state.version)
                // 12 起更新自己不弹确认 (见 SessionInstaller): 系统没要确认之前别让人等一个不会出现的窗口
                hint.setText(if (Build.VERSION.SDK_INT >= 31 && !state.awaitingConfirmation) R.string.installing_silent_hint else R.string.confirm_hint)
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                if (state.awaitingConfirmation) {
                    // 第一个 (默认焦点) 是再打开那个确认窗口; 重试会新建会话, 放后面
                    InstallFlow.pendingConfirmation?.let { confirm ->
                        actions += R.string.button_reopen_confirm to { reopenConfirmation(confirm) }
                    }
                    actions += R.string.button_retry to { InstallFlow.installWithSession(this) }
                    actions += R.string.button_system_installer to { InstallFlow.useSystemInstaller() }
                }
            }

            is InstallFlow.State.Installed -> {
                status.text = getString(R.string.installed_title, state.version)
                hint.setText(R.string.installed_hint)
                actions += R.string.button_open_izuko to ::openInstalledApp
                actions += R.string.button_exit to { finish() }
            }

            is InstallFlow.State.Failed -> {
                status.text = failureText(state.reason)
                detail.text = state.detail.orEmpty()
                val reason = state.reason
                // 系统拦下 / 没能开始安装: 多半是厂商只认自家安装渠道或有黑名单, 告诉用户这个牌子能走哪条路
                if (reason.blockedBySystem()) {
                    hint.setText(BrandAdvice.forDevice(Build.MANUFACTURER, Build.BRAND))
                }
                val needsPermission = reason is InstallFlow.Reason.Install &&
                    (reason.permissionRejected || reason.status == PackageInstaller.STATUS_FAILURE_BLOCKED)
                // 缺授权时先去开开关 (焦点落在它上面), 开完回来再重试
                if (needsPermission && Build.VERSION.SDK_INT >= 26) {
                    actions += R.string.button_allow_unknown_sources to ::openUnknownSourcesSettings
                }
                // 会话没装上: 重试仍用会话 (包坏了被删的话会从头下载); 查版本 / 下载失败: 从头来
                val retrySession = reason is InstallFlow.Reason.Install || reason == InstallFlow.Reason.InstallStart
                actions += R.string.button_retry to {
                    if (retrySession) InstallFlow.installWithSession(this) else InstallFlow.start(this)
                }
                if (state.apk != null) actions += R.string.button_system_installer to { InstallFlow.useSystemInstaller() }
                // 查不到版本 / 下不下来: 手机的网络往往好些
                if (reason == InstallFlow.Reason.Lookup || reason == InstallFlow.Reason.Download) {
                    actions += R.string.button_upload_from_phone to ::showUploadDialog
                    actions += channelButton()
                }
                actions += R.string.button_exit to { finish() }
            }
        }
        // 空着的行不占位置: 失败时下面还有诊断信息, 尽量一屏放下
        for (view in listOf(hint, detail, progressText)) {
            view.visibility = if (view.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        setButtons(actions)
    }

    private fun InstallFlow.Reason.blockedBySystem(): Boolean = when (this) {
        InstallFlow.Reason.InstallStart -> true
        is InstallFlow.Reason.Install -> !permissionRejected && status in listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
        )

        else -> false
    }

    private fun showReport() {
        val lines = DeviceReport.lines(this).map { (label, value) -> getString(label) + ": " + value }
        val half = (lines.size + 1) / 2
        reportLeft.text = lines.take(half).joinToString("\n")
        reportRight.text = lines.drop(half).joinToString("\n")
        report.visibility = View.VISIBLE
    }

    private fun failureText(reason: InstallFlow.Reason): String = when (reason) {
        InstallFlow.Reason.Lookup -> getString(R.string.error_lookup)
        InstallFlow.Reason.Download -> getString(R.string.error_download)
        is InstallFlow.Reason.Storage -> getString(R.string.error_storage, bytes(reason.requiredBytes), bytes(reason.availableBytes))
        InstallFlow.Reason.InstallStart -> getString(R.string.error_install_start)
        is InstallFlow.Reason.Install -> getString(
            when (reason.status) {
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    if (reason.permissionRejected) R.string.error_install_permission else R.string.error_install_aborted
                PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.error_install_blocked
                PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.error_install_conflict
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.error_install_incompatible
                PackageInstaller.STATUS_FAILURE_INVALID -> R.string.error_install_invalid
                PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.error_install_storage
                else -> R.string.error_install_other
            },
        )
    }

    private var shownLabels: List<Int>? = null

    /** 按钮按下时执行的动作, 按位置取: 同一排按钮不重建, 状态变了只换这里. */
    private var shownActions: List<() -> Unit> = emptyList()

    private fun setButtons(actions: List<Pair<Int, () -> Unit>>) {
        val labels = actions.map { it.first }
        shownActions = actions.map { it.second }
        // 同一排按钮不动: 下载时每秒刷新几次进度, 重建的话按下与松开落在两个不同的按钮上, 这一下就丢了
        if (labels == shownLabels && buttons.childCount == labels.size) return
        shownLabels = labels
        buttons.removeAllViews()
        labels.forEachIndexed { index, label ->
            val button = tvButton(label) { shownActions.getOrNull(index)?.invoke() }
            buttons.addView(button, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 换了一排按钮, 焦点落在第一个 —— 第一个是这时最该按的
        buttons.getChildAt(0)?.requestFocus()
    }

    /** 胶囊按钮, 聚焦时白底黑字: 原生按钮的聚焦态在电视上几乎看不出来. */
    private fun tvButton(label: Int, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(28), dp(12), dp(28), dp(12))
        minHeight = 0
        minimumHeight = 0
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        background = StateListDrawable().apply {
            addState(intArrayOf(state_focused), pill(Color.WHITE))
            addState(intArrayOf(state_pressed), pill(Color.WHITE))
            addState(intArrayOf(), pill(Color.argb(0x33, 0xFF, 0xFF, 0xFF)))
        }
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(state_focused), intArrayOf(state_pressed), intArrayOf()),
                intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE),
            ),
        )
        setOnClickListener { onClick() }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat()
        setColor(color)
    }

    /**
     * 「换一种方式安装」(会话安装) 之前先说清楚: 它是「安装」不成功时用的; 确认后助手进程会被系统结束 (要替换的就是它),
     * 系统在后台装, 屏幕上看不到进度, 装好也不提示.
     */
    private fun confirmSessionInstall() {
        AlertDialog.Builder(this)
            .setTitle(R.string.session_install_title)
            .setMessage(R.string.session_install_message)
            .setPositiveButton(R.string.button_start_install) { _, _ -> InstallFlow.installWithSession(this) }
            .setNegativeButton(R.string.button_back, null)
            .show()
    }

    /** 打开刚装上的 Izuko TV (电视桌面的入口优先). */
    private fun openInstalledApp() {
        val intent = (if (Build.VERSION.SDK_INT >= 21) packageManager.getLeanbackLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE) else null)
            ?: packageManager.getLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE)
            ?: return
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun reopenConfirmation(confirm: Intent) {
        try {
            startActivity(confirm)
        } catch (e: Exception) {
            // 会话已经失效 (被系统清掉): 新建一个
            InstallFlow.log("Reopening confirmation failed: ${e.describe()}")
            InstallFlow.installWithSession(this)
        }
    }

    /** 关掉时回到 [onActivityResult]; 装上的话助手进程已经被结束, 收不到. */
    private fun launchSystemInstaller(version: String, apk: File) {
        InstallAttempts.record(this, InstallAttempts.METHOD_SYSTEM, version, apk)
        try {
            startActivityForResult(ApkProvider.installIntent(this, apk), REQUEST_SYSTEM_INSTALLER)
        } catch (e: Exception) {
            // 没有能打开安装包的界面 (部分电视去掉了系统安装器): 换安装会话
            InstallFlow.log("System installer failed: ${e.describe()}")
            InstallFlow.installWithSession(this)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SYSTEM_INSTALLER) return
        InstallFlow.log("System installer closed with result $resultCode, install allowed ${DeviceReport.installAllowed(this)}")
        InstallAttempts.recordResult(this, "install screen closed, result $resultCode")
        InstallFlow.onSystemInstallerClosed(this)
    }

    /** 上次交给系统安装却没装上 (助手还在): 正常流程里也把原因写出来, 用户不必等到出错才看见诊断信息. */
    private fun showPreviousAttempt() {
        val attempt = InstallAttempts.previous(this) ?: return
        detail.text = getString(R.string.last_attempt_failed, InstallAttempts.describe(this, attempt))
    }

    /** 「版本：正式版 / 测试版」, 点开选频道. */
    private fun channelButton(): Pair<Int, () -> Unit> =
        (if (InstallFlow.channel == ReleaseChannel.TEST) R.string.button_channel_test else R.string.button_channel_stable) to ::showChannelDialog

    /** 选装正式版还是测试版, 每项写着最新的版本号 (查到了的话) 与两者的差别. */
    private fun showChannelDialog() {
        val versions = InstallFlow.channelVersions
        val items = arrayOf(
            getString(R.string.channel_stable, versions[ReleaseChannel.STABLE].orEmpty()).trim() + "\n" + getString(R.string.channel_stable_desc),
            getString(R.string.channel_test, versions[ReleaseChannel.TEST].orEmpty()).trim() + "\n" + getString(R.string.channel_test_desc),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.channel_title)
            .setSingleChoiceItems(items, if (InstallFlow.channel == ReleaseChannel.TEST) 1 else 0) { dialog, which ->
                dialog.dismiss()
                InstallFlow.setChannel(this, if (which == 1) ReleaseChannel.TEST else ReleaseChannel.STABLE)
            }
            .setNegativeButton(R.string.button_close, null)
            .show()
    }

    /** 各条线路的速度; 选一条只从它下 (失败时退回自动), 也可以回到自动或重新测速. */
    private fun showLinesDialog() {
        val lines = InstallFlow.lineStatuses()
        if (lines.isEmpty()) return
        val manual = InstallFlow.isManualLine()
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        items += getString(R.string.lines_auto) + if (manual) "" else getString(R.string.line_selected)
        actions += { InstallFlow.chooseLine(null) }
        for (line in lines) {
            items += lineLabel(line) + if (manual && line.current) getString(R.string.line_selected) else ""
            actions += { InstallFlow.chooseLine(line.url) }
        }
        items += getString(R.string.lines_retest)
        actions += { InstallFlow.retestLines() }
        AlertDialog.Builder(this)
            .setTitle(R.string.lines_title)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.button_close, null)
            .show()
    }

    private fun lineLabel(line: Downloader.LineStatus): String {
        val parts = mutableListOf(line.host)
        if (line.current) parts += getString(R.string.line_current)
        when {
            line.error != null -> parts += getString(R.string.line_failed, line.error.take(60))
            line.probing -> parts += getString(R.string.line_probing)
            line.probeBytesPerSecond != null -> parts += getString(R.string.line_probe, bytes(line.probeBytesPerSecond))
        }
        line.measuredBytesPerSecond?.let { parts += getString(R.string.line_measured, bytes(it)) }
        return parts.joinToString(" · ")
    }

    private var uploadDialog: AlertDialog? = null

    /** 「用手机传安装包」: 开局域网小服务, 显示二维码与地址, 随时显示收到哪了; 收下并核对通过后自己关掉. */
    private fun showUploadDialog() {
        val address = try {
            UploadServer.start(this)
        } catch (e: UploadServer.StartException) {
            AlertDialog.Builder(this)
                .setTitle(R.string.upload_title)
                .setMessage(if (e.noNetwork) getString(R.string.upload_no_network) else getString(R.string.upload_start_failed, e.message))
                .setNegativeButton(R.string.button_close, null)
                .show()
            return
        }
        // 文字用对话框主题的颜色 (系统对话框有深有浅)
        val builder = AlertDialog.Builder(this)
        val themed = builder.context
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        fun dialogText() = TextView(themed).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
        }
        content.addView(dialogText().apply { setText(R.string.upload_hint) })
        val qrSize = dp(200)
        val quiet = dp(10)
        qrBitmap(address, qrSize)?.let { bitmap ->
            content.addView(
                ImageView(themed).apply {
                    setImageBitmap(bitmap)
                    setBackgroundColor(Color.WHITE)
                    setPadding(quiet, quiet, quiet, quiet)
                },
                LinearLayout.LayoutParams(qrSize + quiet * 2, qrSize + quiet * 2).apply {
                    topMargin = dp(12)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }
        content.addView(dialogText().apply { text = getString(R.string.upload_url, address) }, margins(top = 8))
        val statusLine = dialogText().apply { alpha = 0.75f }
        content.addView(statusLine, margins(top = 12))
        val dialog = builder
            .setTitle(R.string.upload_title)
            .setView(content)
            .setNegativeButton(R.string.button_close, null)
            .create()
        dialog.setOnDismissListener {
            UploadServer.listener = null
            UploadServer.stop()
            if (uploadDialog === dialog) uploadDialog = null
        }
        uploadDialog = dialog
        UploadServer.listener = { status ->
            statusLine.text = when (status) {
                UploadServer.Status.Waiting -> getString(R.string.upload_waiting)
                is UploadServer.Status.Receiving -> getString(R.string.upload_receiving, bytes(status.received), bytes(status.total))
                UploadServer.Status.Checking -> getString(R.string.upload_checking)
                is UploadServer.Status.Rejected -> getString(R.string.upload_rejected, status.message)
                is UploadServer.Status.Accepted -> getString(R.string.upload_accepted, status.version)
            }
            if (status is UploadServer.Status.Accepted) dialog.dismiss()
        }
        dialog.show()
    }

    private fun openUnknownSourcesSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            // 8.0 以前和部分电视没有按应用授权的设置页 (未知来源是全局开关), 打开总的安全设置
            try {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e2: Exception) {
                InstallFlow.log("Install permission settings unavailable: ${e2.describe()}")
                if (InstallFlow.state is InstallFlow.State.NeedsPermission) {
                    // 这台电视没有授权的设置页: 「继续」排第一, 安装时由安装界面去问
                    InstallFlow.onPermissionSettingsUnavailable()
                } else {
                    detail.text = e2.describe()
                    detail.visibility = View.VISIBLE
                }
                return
            }
        }
        InstallFlow.onPermissionSettingsOpened()
    }

    private fun text(sizeSp: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    private fun margins(top: Int, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun bytes(value: Long): String = when {
        value >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / 1024.0 / 1024.0)
        value >= 1024 -> String.format(Locale.ROOT, "%.0f KB", value / 1024.0)
        else -> "$value B"
    }

    private companion object {
        private val MUTED = Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
        private const val REQUEST_SYSTEM_INSTALLER = 1
    }
}
