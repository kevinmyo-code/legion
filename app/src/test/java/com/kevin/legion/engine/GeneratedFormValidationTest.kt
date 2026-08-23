package com.kevin.legion.engine

import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure coverage for [GeneratedFormValidation] - no Robolectric, no Room, no Compose (see that
 * object's own doc comment for why it is written to allow exactly this). */
class GeneratedFormValidationTest {
    private fun field(id: Long, type: FieldType, required: Boolean = false) = FieldDef(
        id = id, recordTypeId = 1L, name = "field$id", type = type, required = required,
        createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun `a required field left absent is an error`() {
        val fields = listOf(field(1, FieldType.TEXT, required = true))
        val errors = GeneratedFormValidation.validate(fields, emptyMap())
        assertEquals(1, errors.size)
        assertTrue(errors.first().message.contains("required"))
    }

    @Test
    fun `a required field with a blank string is an error`() {
        val fields = listOf(field(1, FieldType.TEXT, required = true))
        val errors = GeneratedFormValidation.validate(fields, mapOf(1L to "   "))
        assertEquals(1, errors.size)
    }

    @Test
    fun `a required field with a real value passes`() {
        val fields = listOf(field(1, FieldType.TEXT, required = true))
        val errors = GeneratedFormValidation.validate(fields, mapOf(1L to "hello"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `an optional field left absent is not an error`() {
        val fields = listOf(field(1, FieldType.TEXT, required = false))
        val errors = GeneratedFormValidation.validate(fields, emptyMap())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a money field given a String instead of a Number is a type error`() {
        val fields = listOf(field(1, FieldType.MONEY_CENTS))
        val errors = GeneratedFormValidation.validate(fields, mapOf(1L to "not a number"))
        assertEquals(1, errors.size)
        assertTrue(errors.first().message.contains("unexpected value type"))
    }

    @Test
    fun `a money field given a real Long passes`() {
        val fields = listOf(field(1, FieldType.MONEY_CENTS))
        val errors = GeneratedFormValidation.validate(fields, mapOf(1L to 1200L))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a boolean field given a non-Boolean is a type error`() {
        val fields = listOf(field(1, FieldType.BOOLEAN))
        val errors = GeneratedFormValidation.validate(fields, mapOf(1L to "yes"))
        assertEquals(1, errors.size)
    }

    @Test
    fun `a multi-select field given a non-List is a type error`() {
        val fields = listOf(field(1, FieldType.MULTI_SELECT_CHOICE))
        val errors = GeneratedFormValidation.validate(fields, mapOf(1L to "not a list"))
        assertEquals(1, errors.size)
    }

    @Test
    fun `a multi-select field given a real List passes`() {
        val fields = listOf(field(1, FieldType.MULTI_SELECT_CHOICE))
        val errors = GeneratedFormValidation.validate(fields, mapOf(1L to listOf("a", "b")))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `computed fields are never validated - they are never user-supplied`() {
        val fields = listOf(field(1, FieldType.COMPUTED, required = true))
        val errors = GeneratedFormValidation.validate(fields, emptyMap())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `multiple problems on multiple fields all surface, in field order`() {
        val fields = listOf(
            field(1, FieldType.TEXT, required = true),
            field(2, FieldType.MONEY_CENTS, required = true),
        )
        val errors = GeneratedFormValidation.validate(fields, mapOf(2L to "bad"))
        assertEquals(2, errors.size)
        assertEquals(1L, errors[0].fieldId)
        assertEquals(2L, errors[1].fieldId)
    }
}
