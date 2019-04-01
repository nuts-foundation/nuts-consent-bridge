/*
 * Nuts consent bridge
 * Copyright (C) 2019 Nuts community
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

import com.esotericsoftware.kryo.KryoException
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.utilities.NetworkHostAndPort
import org.apache.activemq.artemis.api.core.SimpleString
import rx.Notification
import rx.exceptions.OnErrorNotImplementedException
import java.util.*

/**
 * Custom serialization whitelist since Comparable posed a problem during test.
 */
class TestSerializationWhiteList : SerializationWhitelist {
    override val whitelist =
            listOf(Array<Any>(0, {}).javaClass,
                    Notification::class.java,
                    Notification.Kind::class.java,
                    ArrayList::class.java,
                    listOf<Any>().javaClass, // EmptyList
                    Pair::class.java,
                    ByteArray::class.java,
                    UUID::class.java,
                    LinkedHashSet::class.java,
                    setOf<Unit>().javaClass, // EmptySet
                    Currency::class.java,
                    listOf(Unit).javaClass, // SingletonList
                    setOf(Unit).javaClass, // SingletonSet
                    mapOf(Unit to Unit).javaClass, // SingletonSet
                    NetworkHostAndPort::class.java,
                    SimpleString::class.java,
                    KryoException::class.java,
                    StringBuffer::class.java,
                    Unit::class.java,
                    java.io.ByteArrayInputStream::class.java,
                    java.lang.Class::class.java,
                    java.math.BigDecimal::class.java,

                    // Matches the list in TimeSerializers.addDefaultSerializers:
                    java.time.Duration::class.java,
                    java.time.Instant::class.java,
                    java.time.LocalDate::class.java,
                    java.time.LocalDateTime::class.java,
                    java.time.LocalTime::class.java,
                    java.time.ZoneOffset::class.java,
                    java.time.ZoneId::class.java,
                    java.time.OffsetTime::class.java,
                    java.time.OffsetDateTime::class.java,
                    java.time.ZonedDateTime::class.java,
                    java.time.Year::class.java,
                    java.time.YearMonth::class.java,
                    java.time.MonthDay::class.java,
                    java.time.Period::class.java,
                    java.time.DayOfWeek::class.java, // No custom serialiser but it's an enum.
                    java.time.Month::class.java, // No custom serialiser but it's an enum.

                    java.util.Collections.emptyMap<Any, Any>().javaClass,
                    java.util.Collections.emptySet<Any>().javaClass,
                    java.util.Collections.emptyList<Any>().javaClass,
                    java.util.LinkedHashMap::class.java,
                    BitSet::class.java,
                    OnErrorNotImplementedException::class.java,
                    StackTraceElement::class.java,

                    //custom
                    java.lang.Comparable::class.java
            )
}