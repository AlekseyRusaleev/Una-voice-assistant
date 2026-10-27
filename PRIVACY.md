# Privacy policy for Google Play Store

The data that Dicio itself stores about the user are preferences, history and logs. They are kept in local and private databases which are only used by Dicio. Therefore, no such personal data is ever shared with any online service, except for specific preferences as explained shortly. Preferences are used to allow the user to customize their experience. Preferences strictly related to a specific online website might be sent to them for the purpose of enforcing such preference (for example, the language preference is used to download the correct Speech-To-Text model). History is used to make relevant suggestions and better understand user input. Logs are used only for debugging and troubleshooting purposes, and do not contain any specific user information.

Dicio contains multiple skills, that is separate components that provide services, so that users can interact with those using the assistant interface (for example weather, lyrics, ...). Some skills work without ever connecting to the internet, while others may connect to various online websites as a requirement for providing their service (for example, the weather skill needs to access the internet to obtain weather data). Skills will not share any personal information with online services, unless it is strictly necessary for them to function (for example, when the weather skill makes a web request, the city is necessarily included). Users may enable or disable each skill individually, so they have full control over which online websites may be contacted.

The voice recognition in Dicio works offline, without ever sending audio to any external service. Once the Vosk AI Speech-To-Text model has been downloaded, Dicio can use it without needing to connect to the internet ever again. Therefore, once the STT model is ready, Dicio can be used without an internet connection, except for skills that need to find information on the internet (for example, the timer will work, while the weather will not). If the user wishes to, he can disable Dicio's access to the internet from Android settings, and the app will continue working normally. The user may even disable voice recognition completely from application settings.

The user can easily delete all of his personal data by just clearing the app's storage from Android settings, or by uninstalling the app completely.

## Una-specific additions

- **The recognition journal is sent to Una's server by default.** A fresh install uploads the phrases
  the assistant heard and short audio clips (up to 8 confirmed wake-ups and 20 rejected triggers per
  day) so that the wake word model can be improved. The first run explains this and offers a
  "Не отправлять" button; the switch in "Что я услышала" turns it off at any time, and the app keeps
  working fully offline. Notes, SMS and calendar entries are never uploaded through this channel.
- **Notes** are stored only on the device. They can be exported to a folder the user picks (for
  example an Obsidian vault); nothing is uploaded unless the user explicitly enables the "for
  advanced users" upload card and confirms the dialog.
- **SMS** are sent through the phone's own `SmsManager` when the user grants `SEND_SMS`. The text is
  never sent to Una's server and is not stored by the app; only a local notification confirms it.
- **Calendar** events are written to the calendar chosen on the device when `READ_CALENDAR` and
  `WRITE_CALENDAR` are granted; otherwise the system calendar app is opened with the event
  pre-filled and the user saves it manually.
- **Contacts** are read locally, only to resolve a spoken name into a phone number; the address book
  never leaves the device.

