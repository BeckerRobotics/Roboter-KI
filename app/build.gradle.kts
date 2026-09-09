plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.beckerrobotics.serviceroboter.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.beckerrobotics.serviceroboter.app"
        minSdk = 26 // AICore/Gemini Nano braucht ohnehin ein aktuelles Gerät; Vosk laeuft auch niedriger.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-prototyp"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Offline-Spracherkennung (siehe Recherche-Dokument, Abschnitt 2).
    // Achtung: Modelldateien werden NICHT über Maven bezogen, sondern separat heruntergeladen
    // und ins Projekt (assets/ oder externer Speicher) gelegt (siehe README.md).
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // On-Device-Embeddings für RAG (siehe Recherche-Dokument, Abschnitt 4).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    // PDF-Textextraktion für die Wissensbasis (Memo/PDF, höchste Priorität lt. Anforderung).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Gemini Nano / AICore – Google AI Edge SDK (siehe Recherche-Dokument, Abschnitt 3.2).
    // Wir nutzen hier das Client SDK, das auf kompatiblen Geräten automatisch Gemini Nano via AICore nutzt.
    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")

    // Für den Online-Fallback (Stufe 3).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
