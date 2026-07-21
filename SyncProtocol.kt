package com.familytree.app.bluetooth

import com.familytree.app.data.Person
import com.familytree.app.data.Relationship
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Fixed SDP UUID both app installs use to find each other's Family Tree RFCOMM service. */
val FAMILY_TREE_SERVICE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
const val FAMILY_TREE_SERVICE_NAME = "FamilyTreeSync"

@Serializable
data class SyncPayload(
    val senderDeviceId: String,
    val people: List<Person>,
    val relationships: List<Relationship>
)

object SyncProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: SyncPayload): String = json.encodeToString(payload)

    fun decode(raw: String): SyncPayload = json.decodeFromString(SyncPayload.serializer(), raw)
}
