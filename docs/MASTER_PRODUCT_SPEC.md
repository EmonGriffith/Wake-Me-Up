# Wake Me Up — Master Product Specification

## 1. Product purpose
Build a free Android app that helps passengers avoid missing their public-transport stop when using Google Maps, especially if they are tired, distracted, or asleep.

Google Maps remains responsible for navigation, GPS, route calculation, and deciding how many stops remain. Our app should monitor relevant Google Maps Android notifications and turn a matching notification into a loud alarm.

Core idea: turn a Google Maps “2 stops remaining” notification into a wake-up alert.

## 2. Core principles
- Free to run: target €0 recurring cost.
- No hosting, backend, cloud database, account, or subscription for the core app.
- No Google Maps Platform API unless later proven necessary.
- Process notification content locally on the device.
- Do not upload notifications, location, or travel history.
- No ads or unnecessary permissions.
- Google Maps remains the navigation app; we do not modify its code.

## 3. Proposed architecture
Google Maps navigation → Android notification → Android NotificationListenerService (with the user's explicit permission) → notification filtering and parsing → trigger rules → local alarm with sound, vibration, and visual alert.

Use Android's notification listener interface. Filter notifications by the Google Maps app package identifier and inspect the notification title, text, and relevant metadata that Android exposes. Do not assume the exact notification format before testing it on the target phone.

## 4. Core behaviour
Example:
- Google Maps posts a public-transport navigation notification such as “2 stops remaining”.
- The app identifies it as a Google Maps notification.
- The app determines whether the content matches the configured trigger.
- If the trigger is met, the app starts a prominent alarm.

Default trigger for the first alarm-capable version: 2 stops remaining.

## 5. Initial user experience
On first launch, explain in plain language that Android notification access is needed to detect Google Maps navigation notifications. The user must explicitly grant access in Android Settings.

The main screen should show:
- Monitoring status: ON/OFF
- Notification access status
- Trigger setting (eventually 3, 2, or 1 stops)
- Sound ON/OFF
- Vibration ON/OFF
- Test Alarm button
- A clear way to stop an active alarm

## 6. Notification detection and parsing
Initially process only notifications from Google Maps; ignore other apps. Keep the Maps package identifier in one configuration location rather than repeating it throughout the code.

Attempt to identify:
- number of stops remaining
- destination or next stop, if exposed
- arrival-related text, if exposed

Do not assume wording or structure is stable. It may vary by Maps version, Android version, language, country, transport type, or app settings. Build parsing rules around observed data and make them easy to adjust.

Important: Android may not expose every piece of information shown visually by Maps. If the notification does not contain a reliable stop count, do not pretend detection works; test alternatives before deciding on the design.

## 7. Alarm behaviour
When a matching notification is detected:
- Play a clearly audible alarm.
- Vibrate if enabled and supported.
- Show a prominent alarm notification or screen.
- Work with the screen off where Android permits.
- Provide a user-controlled stop/dismiss action.
- Avoid endlessly repeating alarms due to duplicate notification updates.
- Avoid triggering repeatedly from the same notification unless configured.

A future version may support a spoken message such as “Wake up — two stops remaining.”

## 8. Privacy and permissions
- Process notification data locally.
- Do not upload notification contents or location.
- Do not collect or store travel history.
- Do not request location permission for the notification-listener MVP.
- Explain notification access clearly; it can expose notifications from other apps at the Android permission level, so the app must filter and process only Google Maps notifications.
- Do not claim that notification access is limited to Maps if Android grants broader access.

## 9. Cost
Target €0 recurring cost:
- No hosting/server.
- No cloud database.
- No paid API.
- No Google Maps API for the notification-listener approach.
- No account or subscription.
- Build and install an APK locally for personal testing.
Publishing to Google Play is optional and may involve separate developer-account requirements/fees, so direct APK installation is the initial route.

## 10. Development stages

Version scope update: v0.1 includes notification inspection, recognition of an observed two-stops-remaining signal, and a working alarm with a test button and dismissal. These remain sequential implementation stages within the same version. User-selectable stop thresholds come later. The real-device notification experiment remains mandatory.
Stage 0 — Investigation:
Check what the installed Google Maps version actually posts during public-transport navigation. Establish whether the stop count appears in an Android notification accessible to a notification listener.

Stage 1 — Notification inspection prototype:
Create a small Android app that listens for notifications after permission is granted and displays/logs locally only the relevant Google Maps notification fields. Do not build the alarm yet.

Stage 2 — Stop recognition:
Recognise “2 stops remaining” or the actual wording/metadata observed on the test phone. Include a safe debug view and avoid triggering on unrelated Maps notifications.

Stage 3 — Alarm:
Add sound, vibration, visual alert, dismissal, and duplicate-trigger protection.

Stage 4 — Reliability tests:
Test screen off/locked, Maps in background, battery saver, app process lifecycle, notification updates, and multiple journeys. Document Android-version-specific limitations.

Stage 5 — Settings:
Add trigger threshold, sound/vibration options, repeat duration, test alarm, and monitoring toggle.

## 11. MVP success criteria
The alarm-capable MVP is successful when:
1. The APK installs on the target Android phone.
2. The user grants notification access.
3. The user starts a Google Maps public-transport journey.
4. A relevant stop-count notification is posted and exposed to the listener.
5. The app recognises the configured threshold (initially 2 stops).
6. The app plays a loud alarm and vibration if enabled.
7. The user can stop the alarm.
8. Tests confirm that unrelated notifications do not trigger it.

## 12. Explicitly out of scope for MVP
- Replacing Google Maps or modifying its app code.
- GPS-based geofencing.
- Route planning or route calculation.
- Google Maps APIs.
- User accounts or cloud sync.
- Backend, hosting, or database.
- Ads, subscriptions, or payments.
- AI or machine learning.
- Travel-history tracking.
- Support for other navigation apps.
- Complex UI.

## 13. Risks and honest limitations
- Google Maps may not expose the “stops remaining” text in the notification on every device or version.
- Notification formats may change.
- Android background execution and alarm/audio behaviour vary by version and device manufacturer.
- “Maximum volume” cannot always be forced by an app; Android volume settings and Do Not Disturb policies may limit sound. The app should explain any required user settings rather than silently claiming guaranteed maximum volume.
- A notification-based trigger can only react when the expected notification is posted and contains enough information. It cannot guarantee the passenger will never miss a stop.
- Test on real journeys before relying on it.

## 14. Long-term possibilities
If the notification-based MVP works, later versions may add configurable thresholds (3/2/1 stops), voice alerts, snooze/repeat options, or support for other apps. A GPS/geofence fallback could be explored separately if notification content is insufficient; it would add location-permission and battery considerations.

## 15. Product philosophy
Simple. Free. Private. Local. Reliable.

Google Maps navigates. Android exposes notifications. Our app turns a relevant notification into an alarm.

The product solves one specific problem: “I don't want to miss my stop because I fell asleep or wasn't looking at my phone.”

