# Wake Me Up — MVP Plan

## The most important unknown
Before building the full app, verify that Android actually exposes the relevant Google Maps public-transport stop-count notification to a notification listener on the target phone. Do not assume “2 stops remaining” is available in notification text until observed.

## MVP v0.1 — Inspect, then trigger a fixed alarm
Goal: prove what notification data is accessible, then trigger a working alarm at a fixed two stops remaining. Complete both phases within v0.1; selectable thresholds come later.

Build a minimal Android app that:
1. Explains notification access.
2. Opens Android's notification-access settings.
3. Uses NotificationListenerService after permission is granted.
4. Filters to Google Maps notifications.
5. Displays the observed title, text, package name, and useful metadata locally for debugging.
6. Does not upload or store notification data.
7. Keeps inspection data in memory only. The first phase establishes the real notification format before automatic alarm detection is implemented.

Acceptance test:
- Start a Google Maps public-transport journey.
- Wait for a stop-count/navigation notification.
- Confirm whether the app receives it and what exact fields are available.
- Check whether updates are posted as the journey progresses.

If no usable stop count is exposed, pause and investigate alternatives before proceeding.

### MVP v0.1, second phase — One trigger + alarm
Goal: turn one known notification into an alarm.

Add:
- One rule for the observed “2 stops remaining” equivalent.
- Loud alarm sound, subject to Android sound/volume settings.
- Vibration.
- Prominent alarm notification or screen.
- Stop/dismiss action.
- Test Alarm button to verify sound and dismissal independently of Maps. A manual test does not prove Maps detection works.
- Duplicate-notification protection.
- Local debug status showing why a trigger did or did not fire.

Acceptance test:
- A matching notification triggers the alarm.
- An unrelated Google Maps notification does not trigger it.
- Notifications from other apps do not trigger it.
- The alarm can be dismissed.
- The same notification update does not cause uncontrolled repeated alarms.
- Confirm a real Maps notification triggers the alarm with the phone locked and Maps in the background before relying on it.

## Later versions — Selectable stops, basic settings, and extended reliability
Add:
- Monitoring ON/OFF.
- Notification-access status.
- Sound ON/OFF.
- Vibration ON/OFF.
- Trigger setting if more than one threshold has been verified.
- Basic instructions for battery/background settings if required on the phone.

Test:
- Screen on and screen off.
- Google Maps in the foreground and background.
- Phone locked.
- Battery saver on/off.
- More than one journey.
- Different stop-count notification updates.
- Reboot/reopen behaviour where relevant.

## Keep the first version deliberately small
Do not build accounts, server, cloud database, GPS tracking, route planning, paid APIs, ads, subscriptions, AI, or multi-app support.

## Free development target
- Android app developed locally.
- Build an APK and sideload it onto the test phone.
- No hosting or API needed for the notification-listener approach.
- Do not publish to the Play Store for the first test.

## Go/no-go decision
GO if the target phone exposes a stable enough stop-count signal and the alarm triggers reliably in real tests.

NO-GO for the notification-only design if Maps does not expose a usable stop-count signal. In that case, investigate a separate GPS/geofence fallback or other legitimate Android options before investing in more features.


