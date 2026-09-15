package com.nextnotif.app

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType

internal fun phoneContactPickerIntent() =
    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

@Composable
internal fun ContactPhoneField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    isError: Boolean = false) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (column >= 0) cursor.getString(column)?.takeIf(String::isNotBlank)?.let(onValueChange)
                }
            }
        }
    }
    OutlinedTextField(value, onValueChange, modifier, singleLine = true,
        label = { Text(stringResource(R.string.sms_number_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        isError = isError,
        trailingIcon = {
            IconButton(onClick = {
                picker.launch(phoneContactPickerIntent())
            }) {
                Icon(Icons.Filled.Person, stringResource(R.string.choose_contact))
            }
        })
}
