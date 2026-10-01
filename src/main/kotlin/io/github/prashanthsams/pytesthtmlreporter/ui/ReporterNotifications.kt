package io.github.prashanthsams.pytesthtmlreporter.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterIcons
import org.jetbrains.annotations.Nls

internal object ReporterNotifications {
    private const val GROUP_ID = "pytest-html-reporter"

    /** [content] is plain text: paths, URLs and Python errors are escaped, line breaks kept. */
    fun notify(
        project: Project?,
        @Nls content: String,
        type: NotificationType = NotificationType.INFORMATION,
        @Nls title: String = PytestHtmlReporterBundle.message("notification.title"),
        actions: List<NotificationAction> = emptyList(),
    ): Notification {
        val html = StringUtil.escapeXmlEntities(content).replace("\n", "<br>")
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, html, type)
            .setIcon(PytestHtmlReporterIcons.Reporter)
        actions.forEach(notification::addAction)
        notification.notify(project)
        return notification
    }

    fun action(@Nls text: String, run: () -> Unit): NotificationAction =
        NotificationAction.createSimpleExpiring(text) { run() }
}
