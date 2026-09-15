package ie.claudius.cardash.media;

import android.service.notification.NotificationListenerService;

/**
 * Deliberately empty.
 *
 * MediaSessionManager.getActiveSessions() will only talk to a caller
 * that holds notification-listener access, and the only way to hold it
 * is to declare a NotificationListenerService. We never read a single
 * notification — this exists purely as the key to the media session
 * API, which is the difference between "fire a media key into the void
 * and hope" and actually knowing what is playing.
 */
public class NotificationListener extends NotificationListenerService {
}
