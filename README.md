Aurora 
Aurora is an Android thesis prototype for peer-to-peer messaging over nearby transports. 
The current codebase focuses on BLE direct messaging, Wi-Fi Direct experiments, and local cryptographic identity management inside a single Android app module. 
Thesis Context This repository is being prepared as a reproducible thesis artifact. 
The goal of this cleanup commit is repository repair and baseline documentation, not protocol redesign or security hardening. 
Current Implementation Status The app builds locally as an Android/Compose application. 
BLE transport code, Wi-Fi Direct transport code, crypto helpers, persistence helpers, and the main chat/view-model flow are present in the repository. 
The repository should now be treated as a prototype baseline rather than a finished production system. 
What Currently Works Single-module Android app with splash/permission flow and Compose UI. 
BLE advertiser, scanner, GATT server/client, and transport coordination code. 
Wi-Fi Direct discovery, protocol, and socket transport code for prototype device-to-device messaging. 
Android Keystore-backed identity helpers and AES-GCM/ECDSA-related crypto utilities. 
Local preference-based persistence for usernames, contacts, and chat state. 
What Is Still Prototype / Skeleton AppViewModel.kt remains a very large orchestration point and still needs architectural decomposition. 
Room dependencies and entity/converter files exist, but the persistence layer is not yet fully migrated to a Room-backed runtime data model. 
AuroraApplication.kt is currently empty and not wired as an active application entry point. 
Automated testing is still minimal. BLE and Wi-Fi Direct behavior on real devices is still under validation and should not be overstated in thesis claims. 
Build Use Android Studio JBR or another compatible JDK, then run: $env:JAVA_HOME='C:\Users\chris\AppData\Local\Programs\Android Studio\jbr' $env:GRADLE_USER_HOME="$PWD\.gradle-user-home" .\gradlew.bat assembleDebug --rerun-tasks --console=plain 
Test For local unit tests: $env:JAVA_HOME='C:\Users\chris\AppData\Local\Programs\Android Studio\jbr' $env:GRADLE_USER_HOME="$PWD\.gradle-user-home" .\gradlew.bat testDebugUnitTest --rerun-tasks --console=plain 
Instrumented tests require a connected Android device or emulator: $env:JAVA_HOME='C:\Users\chris\AppData\Local\Programs\Android Studio\jbr' $env:GRADLE_USER_HOME="$PWD\.gradle-user-home" .\gradlew.bat connectedDebugAndroidTest --console=plain 
Validation Warning Real-device BLE and Wi-Fi Direct behavior is still being validated. 
This repository should currently be presented as a thesis prototype with known limitations, not as a finished secure messaging product.
