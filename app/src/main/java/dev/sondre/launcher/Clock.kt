package dev.sondre.launcher

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.Date
import java.util.Locale

/** Big time with the date underneath. Purely visual; taps are handled by the caller. */
@Composable
fun Clock(
    timeSize: TextUnit,
    dateSize: TextUnit,
    color: Color,
    font: ClockFont,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val now = rememberNow()
    val is24h = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val time = DateFormat.format(if (is24h) "HH:mm" else "h:mm", now).toString()
    // e.g. "fredag 20. november" / "Friday, November 20", following the phone's language.
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), Date(now)).toString()

    val shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), blurRadius = 8f)
    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        Text(
            text = time,
            color = color,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                fontFamily = font.family,
                fontWeight = font.weight,
                fontSize = timeSize,
                lineHeight = timeSize,
                shadow = shadow,
            ),
        )
        // The date stays in the system monospace, smaller and lighter than the time.
        Text(
            text = date,
            color = color.copy(alpha = 0.85f),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = dateSize, shadow = shadow),
        )
    }
}

/** Current time in millis, refreshed every minute, on clock/time-zone changes and on resume. */
@Composable
private fun rememberNow(): Long {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                now = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // System broadcasts still reach a non-exported receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    // TIME_TICK isn't sent while the screen is off, so catch up when we come back.
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    return now
}

/**
 * Opens the full clock app (alarms, timer, stopwatch…), as tapping Samsung's own clock does.
 * The clock app is whichever app handles "show alarms"; we start its normal launcher entry
 * rather than the alarm-list screen. Falls back to the alarm list if it has no launcher entry.
 * Never throws.
 */
fun openClock(context: Context) {
    val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
    val pkg = context.packageManager.resolveActivity(showAlarms, 0)?.activityInfo?.packageName
    val app = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    try {
        context.startActivity((app ?: showAlarms).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No clock app found", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        // Some clock apps guard SHOW_ALARMS behind SET_ALARM (declared in the manifest).
        Toast.makeText(context, "Couldn't open the clock", Toast.LENGTH_SHORT).show()
    }
}
