package com.kaddie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kaddie.app.BuildConfig

/** Visible only in the preprod build, so it can never be mistaken for the real app. */
@Composable
fun EnvironmentBadge(modifier: Modifier = Modifier) {
    if (!BuildConfig.IS_PREPROD) return
    Text(
        text = "PRÉPROD",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onError,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
