package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Data Access Object for [MerchantAlias]. */
@Dao
interface MerchantAliasDao {
    @Insert
    suspend fun insert(alias: MerchantAlias): Long

    /** Whole-row update: the sync merge write and the local soft-delete. */
    @Update
    suspend fun update(alias: MerchantAlias)

    /** Live aliases, oldest first. Oldest wins on overlap, so the order is part of the contract. */
    @Query("SELECT * FROM merchant_aliases WHERE deleted = 0 ORDER BY createdAt ASC, id ASC")
    fun observeActive(): Flow<List<MerchantAlias>>

    @Query("SELECT * FROM merchant_aliases WHERE deleted = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun getActive(): List<MerchantAlias>

    /** Every row, tombstones included: the sync pull's local match scan. */
    @Query("SELECT * FROM merchant_aliases")
    suspend fun getAllIncludingDeleted(): List<MerchantAlias>

    @Query("SELECT * FROM merchant_aliases WHERE id = :id")
    suspend fun getById(id: Long): MerchantAlias?
}
