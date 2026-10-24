package com.ayush.armour;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing with notifications. Being an enabled listener is what lets Armour see other apps' media
 * sessions (what Spotify is playing, its cover, play/next). Enabled over adb by install.sh.
 */
public final class MediaListener extends NotificationListenerService {
}
