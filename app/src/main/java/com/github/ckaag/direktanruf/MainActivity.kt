@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)

package com.github.ckaag.direktanruf

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat.getSystemService
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.github.ckaag.AppConfig
import com.github.ckaag.CallOption
import com.github.ckaag.direktanruf.ui.theme.DirektanrufTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DirektanrufTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ListOfItems()
                }
            }
        }
    }
}

object SettingsSerializer : Serializer<AppConfig> {
    override val defaultValue: AppConfig = AppConfig.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): AppConfig {
        try {
            return AppConfig.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: AppConfig,
        output: OutputStream
    ) = t.writeTo(output)
}

private val AppConfig.options: List<CallOption>
    get() = this.optionsList.toList()
val Context.settingsDataStore: DataStore<AppConfig> by dataStore(
    fileName = "settings.proto",
    serializer = SettingsSerializer
)

private val defaultAppConfig = AppConfig.getDefaultInstance()!!

@SuppressLint("MissingPermission")
@Composable
fun ListOfItems() {
    val context = LocalContext.current
    var appConfig by remember { mutableStateOf(defaultAppConfig) }


    var isEdit by remember { mutableStateOf(false) }

    var listOfSims by remember { mutableStateOf<List<String>>(listOf()) }
    val scope = rememberCoroutineScope()
    val subManager = getSystemService(context, SubscriptionManager::class.java)

    val phoneStatePermissionState = rememberPermissionState(
        permission = Manifest.permission.READ_PHONE_STATE,
        onPermissionResult = { granted ->
            if (granted) {
                if (subManager != null) {
                    val subInfoList = subManager.activeSubscriptionInfoList
                    listOfSims = subInfoList.map { it.subscriptionId.toString() }.toList()
                } else {
                    Log.e("Direktanruf", "subManager null")
                }
            } else {
                print("permission is denied")
            }
        }
    )

    if (listOfSims.isEmpty()) {
        LaunchedEffect(listOfSims) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.settingsDataStore.data.take(1).collect { appConfig = it }
                    if (phoneStatePermissionState.status != PermissionStatus.Granted) {
                        phoneStatePermissionState.launchPermissionRequest()
                    } else {
                        if (subManager != null && listOfSims.isEmpty()) {
                            val subInfoList = subManager.activeSubscriptionInfoList
                            listOfSims = subInfoList.map { it.subscriptionId.toString() }.toList()
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = {
            isEdit = !isEdit
        }) {
            Icon(
                if (isEdit) Icons.Filled.Settings else Icons.Outlined.Settings,
                "switch to configuration mode"
            )
        }
        LazyColumn {
            itemsIndexed(appConfig.optionsList) { idx, item ->
                ListItem(
                    item = item,
                    canChange = isEdit,
                    onUp = {
                        appConfig = buildAppConfig(moveIndexBy(appConfig.options, idx, -1))
                        appConfig.saveSettings(context)
                    },
                    onDown = {
                        appConfig = buildAppConfig(moveIndexBy(appConfig.options, idx, 1))
                        appConfig.saveSettings(context)
                    },
                    onRemove = {
                        appConfig =
                            buildAppConfig(
                                appConfig.options.toMutableList().also { it.removeAt(idx) })
                        appConfig.saveSettings(context)
                    })
            }
        }
        if (isEdit) {
            ItemAdder(
                listOfSims = listOfSims,
                onAdd = {
                    appConfig = buildAppConfig(appConfig.options + (it))
                    appConfig.saveSettings(context)
                })
        }
    }
}

private suspend fun AppConfig.saveSettings(context: Context) {
    context.settingsDataStore.updateData { this }
}

fun buildAppConfig(list: List<CallOption>): AppConfig {
    return AppConfig.newBuilder().addAllOptions(list).build()
}

fun moveIndexBy(list: List<CallOption>, idx: Int, offset: Int): List<CallOption> {
    val newPos = idx + offset
    if (newPos < 0 || newPos >= list.size) {
        return list
    }
    val newList = list.toMutableList()
    Collections.swap(newList, idx, newPos)
    return newList
}

@Composable
fun ItemAdder(onAdd: suspend (CallOption) -> Unit, listOfSims: List<String>) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var sim by remember { mutableStateOf(listOfSims.firstOrNull() ?: "") }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .border(BorderStroke(2.dp, Color.Red))
            .fillMaxWidth()
    ) {
        Column {

            TextField(label = { Text("Kontaktname") }, value = name, onValueChange = { name = it })
            TextField(
                label = { Text("Telefonnummer") },
                value = number,
                onValueChange = { number = it })
            if (!expanded) {
                Button(onClick = { expanded = true }) {
                    Text("SIM: $sim")
                }
            } else {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    listOfSims.forEach { si ->
                        DropdownMenuItem(text = { Text(si) }, onClick = {
                            sim = si
                            expanded = false
                        })
                    }
                }
            }
            if (number.isNotBlank() && sim.isNotBlank()) {
                Button(onClick = {
                    val option =
                        CallOption.newBuilder().setName(name).setNumber(number).setSim(sim)
                            .build()!!
                    name = ""
                    number = ""
                    sim = ""
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            onAdd(option)
                        }
                    }
                }) {
                    Text("Neuen Eintrag hinzufügen")
                }
            }
        }
    }
}

@Composable
fun ListItem(
    item: CallOption,
    canChange: Boolean,
    onUp: suspend () -> Unit,
    onDown: suspend () -> Unit,
    onRemove: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val callPermissionState = rememberPermissionState(
        permission = Manifest.permission.CALL_PHONE,
        onPermissionResult = { granted ->
            if (granted) {
                Log.e("Direktanruf", "given permission")
                scope.launch {
                    withContext(Dispatchers.IO) {
                        direktAnruf(context, item.name, item.sim)
                    }
                }
            } else {
                print("permission is denied")
            }
        }
    )
    Box(
        modifier = Modifier
            .border(BorderStroke(1.dp, Color.Black))
            .fillMaxWidth()
            .padding(3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (callPermissionState.status == PermissionStatus.Granted) {
                                direktAnruf(context, item.name, item.sim)
                            } else {
                                callPermissionState.launchPermissionRequest()
                            }
                        }
                    }
                }, modifier = if (canChange) Modifier else Modifier.fillMaxWidth()
            )
            {
                if (canChange) {
                    Column {
                        Text(text = item.name)
                        Text(text = item.number)
                        Text(text = "SIM: ${item.sim}")
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = item.name, fontSize = 28.sp)
                        Text(text = item.number, fontSize = 20.sp)
                        Text(text = "SIM: ${item.sim}", fontSize = 12.sp)
                    }
                }
            }
            if (canChange) {
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            onUp()
                        }
                    }
                }) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        "Nach oben schieben"
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            onDown()
                        }
                    }
                }) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        "Nach unten schieben"
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            onRemove()
                        }
                    }
                }) {
                    Icon(
                        Icons.Filled.Delete,
                        "Zeile löschen"
                    )
                }
            }
        }
    }
}

fun direktAnruf(context: Context, phoneNumberOrUssd: String, subIdForSlot: String) {
    try {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumberOrUssd"))
        val componentName = ComponentName(
            "com.android.phone",
            "com.android.services.telephony.TelephonyConnectionService"
        )
        val phoneAccountHandle = PhoneAccountHandle(componentName, subIdForSlot)
        intent.putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE", phoneAccountHandle)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }


}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DirektanrufTheme {
        ListOfItems()
    }
}
