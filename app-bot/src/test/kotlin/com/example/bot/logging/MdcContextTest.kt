package com.example.bot.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.MDC

class MdcContextTest :
    StringSpec({
        beforeTest {
            MDC.clear()
        }

        afterTest {
            MDC.clear()
        }

        "withIds nests business IDs without touching trace/span" {
            MDC.put(MdcKeys.TRACE_ID, "trace-1")
            MDC.put(MdcKeys.SPAN_ID, "span-1")

            MdcContext.withIds(clubId = 7L) {
                MDC.get(MdcKeys.CLUB_ID) shouldBe "7"
                MDC.get(MdcKeys.TRACE_ID) shouldBe "trace-1"
                MDC.get(MdcKeys.SPAN_ID) shouldBe "span-1"

                MdcContext.withIds(listId = 11L, entryId = 22L) {
                    MDC.get(MdcKeys.CLUB_ID) shouldBe "7"
                    MDC.get(MdcKeys.LIST_ID) shouldBe "11"
                    MDC.get(MdcKeys.ENTRY_ID) shouldBe "22"
                    MDC.get(MdcKeys.TRACE_ID) shouldBe "trace-1"
                    MDC.get(MdcKeys.SPAN_ID) shouldBe "span-1"
                }

                MDC.get(MdcKeys.LIST_ID).shouldBeNull()
                MDC.get(MdcKeys.ENTRY_ID).shouldBeNull()
                MDC.get(MdcKeys.TRACE_ID) shouldBe "trace-1"
                MDC.get(MdcKeys.SPAN_ID) shouldBe "span-1"
            }

            MDC.get(MdcKeys.CLUB_ID).shouldBeNull()
            MDC.get(MdcKeys.LIST_ID).shouldBeNull()
            MDC.get(MdcKeys.ENTRY_ID).shouldBeNull()
            MDC.get(MdcKeys.BOOKING_ID).shouldBeNull()
            MDC.get(MdcKeys.TRACE_ID) shouldBe "trace-1"
            MDC.get(MdcKeys.SPAN_ID) shouldBe "span-1"
        }
    })
