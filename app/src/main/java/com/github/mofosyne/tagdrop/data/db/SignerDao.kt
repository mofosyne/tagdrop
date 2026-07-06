package com.github.mofosyne.tagdrop.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SignerDao {
    @Query("SELECT * FROM trusted_signers WHERE signerIdHex = :signerIdHex")
    suspend fun getBySignerId(signerIdHex: String): TrustedSigner?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signer: TrustedSigner)

    @Query("DELETE FROM trusted_signers")
    suspend fun deleteAll()
}
