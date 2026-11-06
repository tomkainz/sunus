package de.tomkainz.sonoslive

import android.service.notification.NotificationListenerService

/**
 * Exists only because Android hands out other apps' media sessions to apps with
 * notification access. It deliberately overrides nothing: Sunus never looks at
 * notifications, it only uses the media sessions in [NowPlaying].
 */
class MediaListenerService : NotificationListenerService()
