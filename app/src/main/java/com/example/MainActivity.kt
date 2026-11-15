package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.data.firebase.configureAppCheck
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.BanglaSubTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Configure Firebase App Check with launch intent before any UI, ViewModels, or Firebase calls
    configureAppCheck(this, intent)

    enableEdgeToEdge()
    setContent {
      BanglaSubTheme {
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
          AppNavigation()
        }
      }
    }
  }
}
