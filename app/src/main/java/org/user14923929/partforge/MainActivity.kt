package org.user14923929.partforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.user14923929.partforge.ui.AppViewModel
import org.user14923929.partforge.ui.PartForgeApp
import org.user14923929.partforge.ui.PartForgeTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PartForgeTheme {
                PartForgeApp(vm)
            }
        }
    }
}
