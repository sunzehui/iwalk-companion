package com.iwalk.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.iwalk.companion.ui.IwApp

/**
 * 单 Activity 外壳：全部界面在 Compose 里完成。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IwApp() }
    }
}
