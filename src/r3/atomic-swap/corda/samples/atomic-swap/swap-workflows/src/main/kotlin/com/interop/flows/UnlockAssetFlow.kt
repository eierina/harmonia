package com.interop.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.evminterop.dto.TransactionReceipt
import com.r3.corda.evminterop.dto.encoded
import com.r3.corda.evminterop.services.swap.DraftTxService
import com.r3.corda.evminterop.states.swap.LockState
import com.r3.corda.evminterop.states.swap.UnlockData
import com.r3.corda.evminterop.workflows.eth2eth.GetBlockFlow
import com.r3.corda.evminterop.workflows.eth2eth.GetBlockReceiptsFlow
import com.r3.corda.evminterop.workflows.swap.UnlockTransactionAndObtainAssetFlow
import com.r3.corda.interop.evm.common.trie.PatriciaTrie
import com.r3.corda.interop.evm.common.trie.SimpleKeyValueStore
import net.corda.core.contracts.OwnableState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor
import org.web3j.rlp.RlpEncoder
import org.web3j.rlp.RlpString
import org.web3j.utils.Numeric
import java.math.BigInteger

@StartableByRPC
@InitiatingFlow
class UnlockAssetFlow(
    private val transactionId: SecureHash,
    private val blockNumber: BigInteger,
    private val transactionIndex: BigInteger
) : FlowLogic<SignedTransaction>() {

    @Suppress("ClassName")
    companion object {
        object RETRIEVE : ProgressTracker.Step("Retrieving transaction outputs.")
        object QUERY_BLOCK_HEADER : ProgressTracker.Step("Querying block data.")
        object QUERY_BLOCK_RECEIPTS : ProgressTracker.Step("Querying block receipts.")
        object BUILD_UNLOCK_DATA : ProgressTracker.Step("Building unlock data.")
        object UNLOCK_ASSET : ProgressTracker.Step("Unlocking asset.")

        fun tracker() = ProgressTracker(
            RETRIEVE,
            QUERY_BLOCK_HEADER,
            QUERY_BLOCK_RECEIPTS,
            BUILD_UNLOCK_DATA,
            UNLOCK_ASSET
        )

        val log = loggerFor<UnlockAssetFlow>()
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {

        progressTracker.currentStep = RETRIEVE

        val signedTransaction = serviceHub.validatedTransactions.getTransaction(transactionId)
            ?: throw IllegalArgumentException("Transaction not found for ID: $transactionId")

        val outputStateAndRefs = signedTransaction.tx.outputs.mapIndexed { index, state ->
            StateAndRef(state, StateRef(transactionId, index))
        }

        val lockState = outputStateAndRefs
            .filter { it.state.data is LockState }
            // REVIEW: no need to uaw toStateAndRef
            .map { serviceHub.toStateAndRef<LockState>(it.ref)}
            .singleOrNull() ?: throw IllegalArgumentException("Transaction $transactionId does not have a lock state")

        val assetState = outputStateAndRefs
            .filter { it.state.data !is LockState }
            // REVIEW: no need to uaw toStateAndRef
            .map { serviceHub.toStateAndRef<OwnableState>(it.ref)}
            .singleOrNull() ?: throw IllegalArgumentException("Transaction $transactionId does not have a single asset")

        val signatures: List<DigitalSignature.WithKey> =
            serviceHub.cordaService(DraftTxService::class.java).blockSignatures(blockNumber)

        require(signatures.count() >= lockState.state.data.signaturesThreshold) {
            "Insufficient signatures for this transaction"
        }

        progressTracker.currentStep = QUERY_BLOCK_HEADER

        // Get the block that mined the transaction that generated the designated EVM event
        val block = subFlow(GetBlockFlow(blockNumber, true))

        progressTracker.currentStep = QUERY_BLOCK_RECEIPTS

        // Get all the transaction receipts from the block to build and verify the transaction receipts root
        val receipts = subFlow(GetBlockReceiptsFlow(blockNumber))

        // Get the receipt specifically associated with the transaction that generated the event
        val unlockReceipt = receipts[transactionIndex.toInt()]

        progressTracker.currentStep = BUILD_UNLOCK_DATA

        val merkleProof = generateMerkleProof(receipts, unlockReceipt)

        val unlockData = UnlockData(merkleProof, signatures, block.receiptsRoot, unlockReceipt)

        progressTracker.currentStep = UNLOCK_ASSET

        return subFlow(UnlockTransactionAndObtainAssetFlow(assetState, lockState, unlockData))
    }

    private fun generateMerkleProof(
        receipts: List<TransactionReceipt>,
        unlockReceipt: TransactionReceipt
    ): SimpleKeyValueStore {
        // Build the trie
        val trie = PatriciaTrie()
        for (receipt in receipts) {
            trie.put(
                encodeKey(receipt.transactionIndex!!),
                receipt.encoded()
            )
        }

        return trie.generateMerkleProof(encodeKey(unlockReceipt.transactionIndex))
    }

    private fun encodeKey(key: String?) =
        RlpEncoder.encode(RlpString.create(Numeric.toBigInt(key!!).toLong()))
}



