/*
 * Nuts consent bridge
 * Copyright (C) 2020 Nuts community
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package nl.nuts.consent.bridge

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ValueNode
import nl.nuts.consent.bridge.model.PartyAttachmentSignature
import nl.nuts.consent.bridge.model.SignatureWithKey

class ConsentBridgeModule : SimpleModule() {

    init {
        addDeserializer(PartyAttachmentSignature::class.java, PASDeserializer())
    }

    private class PASDeserializer : StdDeserializer<PartyAttachmentSignature>(PartyAttachmentSignature::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext?): PartyAttachmentSignature =
                p.readValueAsTree<TreeNode>().run {
                    PartyAttachmentSignature(
                            legalEntity = get("legalEntity").asValueNode().textValue(),
                            attachment = get("attachment").asValueNode().textValue(),
                            signature = parseSignature(p, get("signature")!!)
                    )
                }

        fun TreeNode.asValueNode(): ValueNode =
                when {
                    isValueNode -> {
                        this as ValueNode
                    }
                    else -> {
                        throw JsonParseException(null, "Property is not a value")
                    }
                }

        private fun parseSignature(p: JsonParser, node: TreeNode): Any =
                when {
                    node.isValueNode -> {
                        node.asValueNode().textValue()
                    }
                    node.isObject -> {
                        node.traverse(p.codec).readValueAs(SignatureWithKey::class.java)
                    }
                    else -> {
                        throw JsonParseException(p, "Either JWS as string or SignatureWithKey as object was expected")
                    }
                }
    }
}