package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.client.Client;
import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO.VIn;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO.VOut;
import com.dashradar.dashradarbackend.domain.Transaction;
import com.dashradar.dashradarbackend.repository.BlockChainTotalsRepository;
import com.dashradar.dashradarbackend.repository.BlockRepository;
import com.dashradar.dashradarbackend.repository.DayRepository;
import com.dashradar.dashradarbackend.repository.PrivateSendTotalsRepository;
import com.dashradar.dashradarbackend.repository.TransactionInputRepository;
import com.dashradar.dashradarbackend.repository.TransactionOutputRepository;
import com.dashradar.dashradarbackend.repository.TransactionRepository;
import com.dashradar.dashradarbackend.service.BalanceEventService;
import com.dashradar.dashradarbackend.service.DailyPercentilesService;
import com.dashradar.dashradarbackend.service.MultiInputHeuristicClusterService;
import com.dashradar.dashradarbackend.util.TransactionUtil;
import static com.dashradar.dashradarbackend.util.TransactionUtil.isDenomination;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockImportServiceImpl implements BlockImportService {
    
    @Autowired
    private MultiInputHeuristicClusterService multiInputHeuristicClusterService;
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    @Autowired
    private TransactionOutputRepository transactionOutputRepository;
    
    @Autowired
    private TransactionInputRepository transactionInputRepository;
    
    @Autowired
    private Client client;
    
    @Autowired
    private BlockRepository blockRepository;
    
    @Autowired
    private BalanceEventService balanceEventService;
    
    @Autowired
    private BlockChainTotalsRepository blockChainTotalsRepository;
    
    @Autowired
    private PrivateSendTotalsRepository privateSendTotalsRepository;
    
    @Autowired
    private DayRepository dayRepository;
    
    @Autowired
    private DailyPercentilesService dailyPercentilesService;

    private void createPrivateSendChainTotals(BlockDTO block) {
        privateSendTotalsRepository.compute_mixing_100_0_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_10_0_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_1_0_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_1_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_01_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_001_counts(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_tx_count(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_mixing_0_001_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_0_01_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_0_1_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_1_0_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_10_0_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_100_0_output_count(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_mixing_0_001_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_0_01_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_0_1_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_1_0_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_10_0_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_100_0_spent_output_count(block.getHash());
        
        
        privateSendTotalsRepository.compute_mixing_100_0_size(block.getHash());
        privateSendTotalsRepository.compute_mixing_10_0_size(block.getHash());
        privateSendTotalsRepository.compute_mixing_1_0_size(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_1_size(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_01_size(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_001_size(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_tx_input_count(block.getHash());
        //TODO
    }
    
    private void createBlockChainTotals(BlockDTO block) {
        String hash = block.getHash();
        blockChainTotalsRepository.compute_input_counts(hash);
        blockChainTotalsRepository.compute_output_counts(hash);
        blockChainTotalsRepository.compute_total_block_rewards(hash);
        blockChainTotalsRepository.compute_total_block_size(hash);
        blockChainTotalsRepository.compute_total_difficulty(hash);
        blockChainTotalsRepository.compute_total_fees(hash);
        blockChainTotalsRepository.compute_total_output_volume(hash);
        blockChainTotalsRepository.compute_total_transaction_size(hash);
        blockChainTotalsRepository.compute_total_tx_count(hash);
    }
    
    @Override
    @Transactional
    public void processBlock(BlockDTO block, boolean dayChanged) throws IOException {
        
        if (dayChanged) {
            System.out.println("Processing block "+block.getHeight()+" (day changed)");
        } else {
            System.out.println("Processing block "+block.getHeight());
        }
        
        if (dayChanged) {
            dayRepository.setLastBlockOfDay(block.getPreviousblockhash());
            for (double percentile = 0.25; percentile < 1; percentile += 0.25) {
                dailyPercentilesService.createDailyPercentiles(block.getTime()/(60*60*24)-1, percentile);
            }
        }
        
        if (block.getHeight() == 0) {
            System.out.println("creating genesis block");
            blockRepository.createGenesisBlock(block.getBits(), block.getChainwork(), block.getDifficulty(), block.getHash(), block.getHeight(), block.getMediantime(), 
                block.getMerkleroot(), block.getNonce(), block.getSize(), block.getTime(), block.getVersion());
            return;
        }
        Boolean unorhanBlock = blockRepository.unorhanBlock(block.getHash());
        if (unorhanBlock != null && unorhanBlock == true) {
            System.out.println("UNORPHANED BLOCK "+block.getHash());
            balanceEventService.handleUnorphanedBlock(block.getHash());
            return;
        }
        blockRepository.createEmptyBestBlock(block.getBits(), block.getChainwork(), block.getDifficulty(), block.getHash(), block.getHeight(), block.getMediantime(), 
                block.getMerkleroot(), block.getNonce(), block.getSize(), block.getTime(), block.getVersion());
        
        List<String> mempoolTxids = transactionRepository.getMempoolTxids();
        int n = 0;
        for (String txid : block.getTx()) {
            if (mempoolTxids.contains(txid)) {
                transactionRepository.moveMempooTransactionToBlock(txid, block.getHash(), n);
                //move to block
            } else {
                Boolean moved = transactionRepository.moveOrphanedTransactionToBlock(txid, block.getHash(), n);
                if (moved != null && moved == true) {
                    System.out.println("Transaction "+txid+" moved from orphaned block");
                } else {
                    TransactionDTO tx = client.getTrasactionByTxId(txid);
                    int psType = n == 0 ? Transaction.PRIVATE_SEND_NONE : getPsType(tx);
                    transactionRepository.createBlockTransaction(n, tx.getLocktime(), psType, tx.getSize(), txid, tx.getVersion(), block.getHash());
                    for (VIn vin : tx.getVin()) {
                        if (vin.getCoinbase() != null) {
                            transactionInputRepository.createCoinbaseInput(txid, vin.getSequence(), vin.getCoinbase());
                        } else {
                            transactionInputRepository.createTransactionInput(txid, vin.getSequence(), vin.getTxid(), vin.getVout());
                        }
                    }
                    for (VOut vout : tx.getVout()) {
                        List<String> addresses;
                        if (vout.getScriptPubKey() != null && vout.getScriptPubKey().getAddresses() != null) {
                            addresses = Arrays.asList(vout.getScriptPubKey().getAddresses());
                        } else {
                            addresses = new ArrayList<>();
                        }
                        transactionOutputRepository.createTransactionOutput(txid, vout.getN(), vout.getValueSat(), addresses);
                    }
                    if (n > 0) {
                        transactionRepository.compute_tx_fee(txid);
                        if (psType != Transaction.PRIVATE_SEND_MIXING_0_01 && psType != Transaction.PRIVATE_SEND_MIXING_0_1 && 
                                psType != Transaction.PRIVATE_SEND_MIXING_1_0 && psType != Transaction.PRIVATE_SEND_MIXING_10_0 && 
                                psType != Transaction.PRIVATE_SEND_MIXING_100_0) {
                            multiInputHeuristicClusterService.clusterizeTransaction(txid);
                        }
                    }
                }                  
            }
            balanceEventService.createBalances(txid);
            n++;
            //System.out.println("Fetch: "+totalFetchTime+", Create: "+totalCreateTime+", Fee: "+totalTxFeeTime+", Clusterize: "+totalClusterizeTime+", Balance: "+ totalBalancesTime+", PSType: "+totalPsTypeTime);
        }
        createBlockChainTotals(block);
        createPrivateSendChainTotals(block);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_100_0);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_10_0);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_1_0);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_1);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_01);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_001);
        
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_100_0);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_10_0);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_1_0);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_1);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_01);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_001);
        
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_100_0);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_10_0);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_1_0);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_1);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_01);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_001);
    }
    
    
    @Override
    public int getPsType(TransactionDTO tx) {
        if (tx.getVin().size() == 1 && tx.getVin().get(0).getCoinbase() != null) return Transaction.PRIVATE_SEND_NONE;
        if (tx.getVin().size() >= 3 && tx.getVin().size() == tx.getVout().size()) {//Possibly mixing
            long firstValue = tx.getVout().get(0).getValueSat();
            boolean firstValueIsDenom = TransactionUtil.isDenomination(firstValue);

            boolean allOutputsAreSameDenom = firstValueIsDenom && tx.getVout().stream().skip(1).allMatch(vout -> vout.getValueSat() == firstValue);
            if (allOutputsAreSameDenom) {
                boolean mixingTx = allInputsAreSameDenom(firstValue, tx);
                if (mixingTx) {
                    if (firstValue == TransactionUtil.DENOM_0_001) {
                        return Transaction.PRIVATE_SEND_MIXING_0_001;
                    } else if (firstValue == TransactionUtil.DENOM_0_01) {
                        return Transaction.PRIVATE_SEND_MIXING_0_01;
                    } else if (firstValue == TransactionUtil.DENOM_0_1) {
                        return Transaction.PRIVATE_SEND_MIXING_0_1;
                    } else if (firstValue == TransactionUtil.DENOM_1_0) {
                        return Transaction.PRIVATE_SEND_MIXING_1_0;
                    } else if (firstValue == TransactionUtil.DENOM_10_0) {
                        return Transaction.PRIVATE_SEND_MIXING_10_0;
                    } else if (firstValue == TransactionUtil.DENOM_100_0) {
                        return Transaction.PRIVATE_SEND_MIXING_100_0;
                    }
                }    
            }
        }
        if (tx.getVout().size() >= 2) {
            List<Long> nonDenominations = tx.getVout().stream().map(vout -> vout.getValueSat()).filter(valueSat -> !isDenomination(valueSat)).collect(Collectors.toList());
            if (nonDenominations.size() != tx.getVout().size()) {//at least one denomination output
                if (nonDenominations.size() == 1) {
                    return Transaction.PRIVATE_SEND_CREATE_DENOMINATIONS;
                } else if (nonDenominations.size() == 2) {
                     if (nonDenominations.stream().anyMatch(denom -> TransactionUtil.isMakeCollateralInputsOutput(denom))) {
                         return Transaction.PRIVATE_SEND_CREATE_DENOMINATIONS;
                     }
                }
            } 
        }
        if (tx.getVout().size() == 1) {//Possibly PrivateSend
            if (allInputsAreDenoms(tx)) {
                return Transaction.PRIVATE_SEND;
            }
        } 
        if (tx.getVin().size() == 1 && tx.getVout().size() == 1 && TransactionUtil.isCollateralPaymentOutput(tx.getVout().get(0).getValueSat())) {
            VIn vin = tx.getVin().get(0);
            VOut vout = tx.getVout().get(0);
            if (TransactionUtil.isCollateralPaymentOutput(vout.getValueSat())) {
                long inputValue = transactionOutputRepository.getOutputValue(vin.getTxid(), vin.getVout());
                long fee = inputValue-vout.getValueSat();
                if (fee == TransactionUtil.COLLATERAL_PAYMENT || fee == TransactionUtil.COLLATERAL_PAYMENT_LEGACY || fee == TransactionUtil.COLLATERAL_PAYMENT_LEGACY2) {
                    return Transaction.PRIVATE_SEND_COLLATERAL_PAYMENT;
                }
            }
        }
        if (tx.getVout().stream().anyMatch(vout -> TransactionUtil.isMakeCollateralInputsOutput(vout.getValueSat()))) {
            return Transaction.PRIVATE_SEND_MAKE_COLLATERAL_INPUTS;
        }
        return Transaction.PRIVATE_SEND_NONE;
    }
    
    private boolean allInputsAreDenoms(TransactionDTO tx) {
        return tx.getVin().stream().allMatch(vin -> {
            if (vin.getTxid() == null) return false;//from genesis transaction
            try {
                Long outputValue = transactionOutputRepository.getOutputValue(vin.getTxid(), vin.getVout());
                if (outputValue == null) {
                    System.out.println("NULL OUTPUT 1 "+vin.getTxid()+", "+vin.getVout());
                    return false;
                }//throw new RuntimeException("outputValue NULL!");
                return TransactionUtil.isDenomination(outputValue);
            } catch(Exception ex) {
                System.out.println("allInputsAreDenoms error "+ vin.getTxid()+", "+vin.getVout());
                ex.printStackTrace();
                throw new RuntimeException();
            }
        });
    }
    
    private boolean allInputsAreSameDenom(long denom, TransactionDTO tx) {
        return tx.getVin().stream().allMatch(vin -> {
            if (vin.getTxid() == null) return false;//from genesis transaction
            try {
                Long outputValue = transactionOutputRepository.getOutputValue(vin.getTxid(), vin.getVout());
                if (outputValue == null) {
                    System.out.println("NULL OUTPUT 2 "+vin.getTxid()+", "+vin.getVout());
                    return false;
                }//throw new RuntimeException("outputValue NULL! "+ vin.getTxid()+" , "+vin.getVout());
                return outputValue == denom;
            } catch(Exception ex) {
                System.out.println("allInputsAreSameDenom error "+ vin.getTxid()+", "+vin.getVout());
                ex.printStackTrace();
                throw new RuntimeException();
            }
        });
    }

    @Override
    public void processTx(TransactionDTO transaction) {
        multiInputHeuristicClusterService.clusterizeTransaction(transaction.getTxid());
        
    }
      
}
