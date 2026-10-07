package com.example.callguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves phone numbers to contact names and photo URIs using Android's native Contacts Provider.
 * Contacts synced with Google Accounts on the device are automatically included.
 * Also provides Favourites (starred contacts) and live contact search.
 */
object ContactHelper {

    data class ContactDetails(
        val name: String,
        val photoUri: String? = null,
    )

    data class ContactItem(
        val id: String,
        val name: String,
        val phoneNumber: String,
        val photoUri: String? = null,
        val isStarred: Boolean = false,
    )

    private val cache = ConcurrentHashMap<String, ContactDetails?>()

    fun getContact(context: Context, phoneNumber: String?): ContactDetails? {
        if (phoneNumber.isNullOrBlank()) return null

        val normalized = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cache.containsKey(normalized)) {
            return cache[normalized]
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        var details: ContactDetails? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber),
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
            )

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val highResIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                    val thumbIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)

                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
                    val photoUri = if (highResIdx != -1 && !cursor.isNull(highResIdx)) {
                        cursor.getString(highResIdx)
                    } else if (thumbIdx != -1 && !cursor.isNull(thumbIdx)) {
                        cursor.getString(thumbIdx)
                    } else {
                        null
                    }
                    if (!name.isNullOrBlank()) {
                        details = ContactDetails(name = name, photoUri = photoUri)
                    }
                }
            }
        } catch (e: Exception) {
            CallStateLogger.log("CONTACTS", "Error resolving contact: ${e.message}")
        }

        cache[normalized] = details
        return details
    }

    /**
     * Retrieves favourite / starred contacts (ContactsContract.STARRED == 1).
     */
    fun getStarredContacts(context: Context): List<ContactItem> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val list = mutableListOf<ContactItem>()
        val seen = mutableSetOf<String>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1"
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getString(idIdx) else ""
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else "Unknown"
                    val number = if (numIdx != -1) cursor.getString(numIdx) else ""
                    val photo = if (photoIdx != -1) cursor.getString(photoIdx) else null

                    // Deduplicate by contact id or name so each contact person appears only once in favourites
                    val dedupeKey = if (id.isNotBlank()) "id_$id" else name.trim().lowercase()
                    if (number.isNotBlank() && seen.add(dedupeKey)) {
                        list.add(ContactItem(id = id, name = name, phoneNumber = number, photoUri = photo, isStarred = true))
                    }
                }
            }
        } catch (e: Exception) {
            CallStateLogger.log("CONTACTS", "Error querying starred contacts: ${e.message}")
        }
        return list
    }

    /**
     * Searches all Google and device contacts by name or phone number.
     */
    fun searchContacts(context: Context, query: String): List<ContactItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val list = mutableListOf<ContactItem>()
        val seen = mutableSetOf<String>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val pattern = "%$trimmed%"
            val selectionArgs = arrayOf(pattern, pattern)
            val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT 50"

            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val starIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getString(idIdx) else ""
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else "Unknown"
                    val number = if (numIdx != -1) cursor.getString(numIdx) else ""
                    val photo = if (photoIdx != -1) cursor.getString(photoIdx) else null
                    val starred = if (starIdx != -1) cursor.getInt(starIdx) == 1 else false

                    val key = "$name|$number"
                    if (number.isNotBlank() && seen.add(key)) {
                        list.add(ContactItem(id = id, name = name, phoneNumber = number, photoUri = photo, isStarred = starred))
                    }
                }
            }
        } catch (e: Exception) {
            CallStateLogger.log("CONTACTS", "Error searching contacts: ${e.message}")
        }
        return list
    }

    fun clearCache() {
        cache.clear()
    }
}
