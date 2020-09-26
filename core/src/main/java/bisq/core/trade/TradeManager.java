/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.btc.exceptions.AddressEntryException;
import bisq.core.btc.exceptions.TxBroadcastException;
import bisq.core.btc.model.AddressEntry;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.btc.wallet.TxBroadcaster;
import bisq.core.btc.wallet.WalletService;
import bisq.core.dao.DaoFacade;
import bisq.core.filter.FilterManager;
import bisq.core.locale.Res;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OpenOffer;
import bisq.core.offer.OpenOfferManager;
import bisq.core.offer.availability.OfferAvailabilityModel;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.support.dispute.arbitration.arbitrator.ArbitratorManager;
import bisq.core.support.dispute.mediation.mediator.MediatorManager;
import bisq.core.support.dispute.refund.refundagent.RefundAgentManager;
import bisq.core.trade.closed.ClosedTradableManager;
import bisq.core.trade.failed.FailedTradesManager;
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.trade.messages.PeerPublishedDelayedPayoutTxMessage;
import bisq.core.trade.messages.TakeOfferRequest;
import bisq.core.trade.messages.TradeMessage;
import bisq.core.trade.statistics.ReferralIdService;
import bisq.core.trade.statistics.TradeStatisticsManager;
import bisq.core.user.User;
import bisq.core.util.Validator;

import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.BootstrapListener;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendMailboxMessageListener;
import bisq.network.p2p.messaging.DecryptedMailboxListener;

import bisq.common.ClockWatcher;
import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.FaultHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.storage.Storage;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;

import javax.inject.Inject;
import javax.inject.Named;

import com.google.common.util.concurrent.FutureCallback;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import org.bouncycastle.crypto.params.KeyParameter;

import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Getter;
import lombok.Setter;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

public class TradeManager implements PersistedDataHost, DecryptedDirectMessageListener, DecryptedMailboxListener {
    private static final Logger log = LoggerFactory.getLogger(TradeManager.class);

    private final User user;
    @Getter
    private final KeyRing keyRing;
    private final BtcWalletService btcWalletService;
    private final BsqWalletService bsqWalletService;
    private final TradeWalletService tradeWalletService;
    private final OpenOfferManager openOfferManager;
    private final ClosedTradableManager closedTradableManager;
    private final FailedTradesManager failedTradesManager;
    private final P2PService p2PService;
    private final PriceFeedService priceFeedService;
    private final FilterManager filterManager;
    private final TradeStatisticsManager tradeStatisticsManager;
    private final ReferralIdService referralIdService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    @Getter
    private final ArbitratorManager arbitratorManager;
    private final MediatorManager mediatorManager;
    private final RefundAgentManager refundAgentManager;
    private final DaoFacade daoFacade;
    private final ClockWatcher clockWatcher;

    private final Storage<TradableList<Trade>> tradableListStorage;
    private TradableList<Trade> tradableList;
    @Getter
    private final BooleanProperty pendingTradesInitialized = new SimpleBooleanProperty();
    @Setter
    @Nullable
    private ErrorMessageHandler takeOfferRequestErrorMessageHandler;
    @Getter
    private final LongProperty numPendingTrades = new SimpleLongProperty();
    private final DumpDelayedPayoutTx dumpDelayedPayoutTx;
    @Getter
    private final boolean allowFaultyDelayedTxs;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradeManager(User user,
                        KeyRing keyRing,
                        BtcWalletService btcWalletService,
                        BsqWalletService bsqWalletService,
                        TradeWalletService tradeWalletService,
                        OpenOfferManager openOfferManager,
                        ClosedTradableManager closedTradableManager,
                        FailedTradesManager failedTradesManager,
                        P2PService p2PService,
                        PriceFeedService priceFeedService,
                        FilterManager filterManager,
                        TradeStatisticsManager tradeStatisticsManager,
                        ReferralIdService referralIdService,
                        AccountAgeWitnessService accountAgeWitnessService,
                        ArbitratorManager arbitratorManager,
                        MediatorManager mediatorManager,
                        RefundAgentManager refundAgentManager,
                        DaoFacade daoFacade,
                        ClockWatcher clockWatcher,
                        Storage<TradableList<Trade>> storage,
                        DumpDelayedPayoutTx dumpDelayedPayoutTx,
                        @Named(Config.ALLOW_FAULTY_DELAYED_TXS) boolean allowFaultyDelayedTxs) {
        this.user = user;
        this.keyRing = keyRing;
        this.btcWalletService = btcWalletService;
        this.bsqWalletService = bsqWalletService;
        this.tradeWalletService = tradeWalletService;
        this.openOfferManager = openOfferManager;
        this.closedTradableManager = closedTradableManager;
        this.failedTradesManager = failedTradesManager;
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.filterManager = filterManager;
        this.tradeStatisticsManager = tradeStatisticsManager;
        this.referralIdService = referralIdService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.arbitratorManager = arbitratorManager;
        this.mediatorManager = mediatorManager;
        this.refundAgentManager = refundAgentManager;
        this.daoFacade = daoFacade;
        this.clockWatcher = clockWatcher;
        this.dumpDelayedPayoutTx = dumpDelayedPayoutTx;
        this.allowFaultyDelayedTxs = allowFaultyDelayedTxs;

        tradableListStorage = storage;

        p2PService.addDecryptedDirectMessageListener(this);
        p2PService.addDecryptedMailboxListener(this);

        failedTradesManager.setUnfailTradeCallback(this::unfailTrade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void readPersisted() {
        tradableList = new TradableList<>(tradableListStorage, "PendingTrades");
        tradableList.forEach(trade -> {
            trade.setTransientFields(tradableListStorage, btcWalletService);
            Offer offer = trade.getOffer();
            if (offer != null)
                offer.setPriceFeedService(priceFeedService);
        });

        dumpDelayedPayoutTx.maybeDumpDelayedPayoutTxs(tradableList, "delayed_payout_txs_pending");
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedDirectMessageListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey message, NodeAddress peer) {
        NetworkEnvelope networkEnvelope = message.getNetworkEnvelope();
        // The maker received a TakeOfferRequest
        if (networkEnvelope instanceof TakeOfferRequest) {
            TakeOfferRequest takeOfferRequest = (TakeOfferRequest) networkEnvelope;
            log.info("Received TakeOfferRequest from {} with tradeId {} and uid {}",
                    peer, takeOfferRequest.getTradeId(), takeOfferRequest.getUid());

            try {
                Validator.nonEmptyStringOf(takeOfferRequest.getTradeId());
            } catch (Throwable t) {
                log.warn("Invalid TakeOfferRequest " + takeOfferRequest.toString());
                return;
            }

            Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(takeOfferRequest.getTradeId());
            if (!openOfferOptional.isPresent()) {
                return;
            }

            OpenOffer openOffer = openOfferOptional.get();
            if (openOffer.getState() != OpenOffer.State.AVAILABLE) {
                return;
            }

            Offer offer = openOffer.getOffer();
            openOfferManager.reserveOpenOffer(openOffer);
            Trade trade = offer.isBuyOffer() ?
                    new BuyerAsMakerTrade(offer,
                            Coin.valueOf(takeOfferRequest.getTxFee()),
                            Coin.valueOf(takeOfferRequest.getTakerFee()),
                            takeOfferRequest.isCurrencyForTakerFeeBtc(),
                            openOffer.getArbitratorNodeAddress(),
                            openOffer.getMediatorNodeAddress(),
                            openOffer.getRefundAgentNodeAddress(),
                            tradableListStorage,
                            btcWalletService) :
                    new SellerAsMakerTrade(offer,
                            Coin.valueOf(takeOfferRequest.getTxFee()),
                            Coin.valueOf(takeOfferRequest.getTakerFee()),
                            takeOfferRequest.isCurrencyForTakerFeeBtc(),
                            openOffer.getArbitratorNodeAddress(),
                            openOffer.getMediatorNodeAddress(),
                            openOffer.getRefundAgentNodeAddress(),
                            tradableListStorage,
                            btcWalletService);

            initTrade(trade);
            tradableList.add(trade);
            ((MakerTrade) trade).handleTakeOfferRequest(takeOfferRequest, peer, errorMessage -> {
                if (takeOfferRequestErrorMessageHandler != null)
                    takeOfferRequestErrorMessageHandler.handleErrorMessage(errorMessage);
            });
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedMailboxListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Might get called at startup after HS is published. Can be before or after initPendingTrades.
    @Override
    public void onMailboxMessageAdded(DecryptedMessageWithPubKey message, NodeAddress peer) {
        NetworkEnvelope networkEnvelope = message.getNetworkEnvelope();
        if (networkEnvelope instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) networkEnvelope;
            // The mailbox message will be removed inside the tasks after they are processed successfully
            getTradeById(tradeMessage.getTradeId())
                    .ifPresent(trade -> trade.addDecryptedMessageWithPubKey(message));
        } else if (networkEnvelope instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) networkEnvelope;
            if (ackMessage.getSourceType() == AckMessageSourceType.TRADE_MESSAGE) {
                // We remove here the message not in the trade protocol as it might be that the trade is already
                // completed and the protocol is not listening.
                p2PService.removeEntryFromMailbox(message);
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        if (p2PService.isBootstrapped())
            initPendingTrades();
        else
            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onUpdatedDataReceived() {
                    // Get called after onMailboxMessageAdded from initial data request
                    // The mailbox message will be removed inside the tasks after they are processed successfully
                    initPendingTrades();
                }
            });

        getTradesAsObservableList().addListener((ListChangeListener<Trade>) change -> onTradesChanged());
        onTradesChanged();

        getAddressEntriesForAvailableBalanceStream()
                .filter(addressEntry -> addressEntry.getOfferId() != null)
                .forEach(addressEntry -> {
                    log.warn("Swapping pending OFFER_FUNDING entries at startup. offerId={}", addressEntry.getOfferId());
                    btcWalletService.swapTradeEntryToAvailableEntry(addressEntry.getOfferId(), AddressEntry.Context.OFFER_FUNDING);
                });
    }

    public void shutDown() {
        // Do nothing here
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Init pending trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void initPendingTrades() {
        tradableList.forEach(this::initPendingTrade);
        pendingTradesInitialized.set(true);
    }

    private void initPendingTrade(Trade trade) {
        initTrade(trade);
        trade.updateDepositTxFromWallet();
    }

    private void initTrade(Trade trade) {
        initTrade(trade,
                trade.getProcessModel().isUseSavingsWallet(),
                trade.getProcessModel().getFundsNeededForTrade());
    }

    private void initTrade(Trade trade, boolean useSavingsWallet, Coin fundsNeededForTrade) {
        trade.init(p2PService,
                btcWalletService,
                bsqWalletService,
                tradeWalletService,
                daoFacade,
                this,
                openOfferManager,
                referralIdService,
                user,
                filterManager,
                accountAgeWitnessService,
                tradeStatisticsManager,
                arbitratorManager,
                mediatorManager,
                refundAgentManager,
                keyRing,
                useSavingsWallet,
                fundsNeededForTrade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void checkOfferAvailability(Offer offer,
                                       ResultHandler resultHandler,
                                       ErrorMessageHandler errorMessageHandler) {

        if (btcWalletService.isUnconfirmedTransactionsLimitHit() ||
                bsqWalletService.isUnconfirmedTransactionsLimitHit()) {
            String errorMessage = Res.get("shared.unconfirmedTransactionsLimitReached");
            errorMessageHandler.handleErrorMessage(errorMessage);
            log.warn(errorMessage);
            return;
        }

        offer.checkOfferAvailability(getOfferAvailabilityModel(offer), resultHandler, errorMessageHandler);
    }

    // First we check if offer is still available then we create the trade with the protocol
    public void onTakeOffer(Coin amount,
                            Coin txFee,
                            Coin takerFee,
                            boolean isCurrencyForTakerFeeBtc,
                            long tradePrice,
                            Coin fundsNeededForTrade,
                            Offer offer,
                            String paymentAccountId,
                            boolean useSavingsWallet,
                            TradeResultHandler tradeResultHandler,
                            ErrorMessageHandler errorMessageHandler) {

        checkArgument(!wasOfferAlreadyUsedInTrade(offer.getId()));

        OfferAvailabilityModel model = getOfferAvailabilityModel(offer);
        offer.checkOfferAvailability(model,
                () -> {
                    if (offer.getState() == Offer.State.AVAILABLE) {
                        Trade trade = getNewTrade(amount,
                                txFee,
                                takerFee,
                                isCurrencyForTakerFeeBtc,
                                tradePrice,
                                fundsNeededForTrade,
                                offer,
                                paymentAccountId,
                                useSavingsWallet,
                                model);
                        tradableList.add(trade);
                        ((TakerTrade) trade).onTakeOffer();
                        tradeResultHandler.handleResult(trade);
                    }
                },
                errorMessageHandler);
    }

    private Trade getNewTrade(Coin amount,
                              Coin txFee,
                              Coin takerFee,
                              boolean isCurrencyForTakerFeeBtc,
                              long tradePrice,
                              Coin fundsNeededForTrade,
                              Offer offer,
                              String paymentAccountId,
                              boolean useSavingsWallet,
                              OfferAvailabilityModel model) {
        Trade trade = offer.isBuyOffer() ?
                new SellerAsTakerTrade(offer,
                        amount,
                        txFee,
                        takerFee,
                        isCurrencyForTakerFeeBtc,
                        tradePrice,
                        model.getPeerNodeAddress(),
                        model.getSelectedArbitrator(),
                        model.getSelectedMediator(),
                        model.getSelectedRefundAgent(),
                        tradableListStorage,
                        btcWalletService) :
                new BuyerAsTakerTrade(offer,
                        amount,
                        txFee,
                        takerFee,
                        isCurrencyForTakerFeeBtc,
                        tradePrice,
                        model.getPeerNodeAddress(),
                        model.getSelectedArbitrator(),
                        model.getSelectedMediator(),
                        model.getSelectedRefundAgent(),
                        tradableListStorage,
                        btcWalletService);
        trade.setTakerPaymentAccountId(paymentAccountId);
        initTrade(trade, useSavingsWallet, fundsNeededForTrade);
        return trade;
    }

    private OfferAvailabilityModel getOfferAvailabilityModel(Offer offer) {
        return new OfferAvailabilityModel(
                offer,
                keyRing.getPubKeyRing(),
                p2PService,
                user,
                mediatorManager,
                tradeStatisticsManager);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onWithdrawRequest(String toAddress, Coin amount, Coin fee, KeyParameter aesKey,
                                  Trade trade, ResultHandler resultHandler, FaultHandler faultHandler) {
        String fromAddress = btcWalletService.getOrCreateAddressEntry(trade.getId(),
                AddressEntry.Context.TRADE_PAYOUT).getAddressString();
        FutureCallback<Transaction> callback = new FutureCallback<>() {
            @Override
            public void onSuccess(@javax.annotation.Nullable Transaction transaction) {
                if (transaction != null) {
                    log.debug("onWithdraw onSuccess tx ID:" + transaction.getTxId().toString());
                    onTradeCompleted(trade);
                    trade.setState(Trade.State.WITHDRAW_COMPLETED);
                    trade.getTradeProtocol().onWithdrawCompleted();
                    resultHandler.handleResult();
                }
            }

            @Override
            public void onFailure(@NotNull Throwable t) {
                t.printStackTrace();
                log.error(t.getMessage());
                faultHandler.handleFault("An exception occurred at requestWithdraw (onFailure).", t);
            }
        };
        try {
            btcWalletService.sendFunds(fromAddress, toAddress, amount, fee, aesKey, AddressEntry.Context.TRADE_PAYOUT, callback);
        } catch (AddressFormatException | InsufficientMoneyException | AddressEntryException e) {
            e.printStackTrace();
            log.error(e.getMessage());
            faultHandler.handleFault("An exception occurred at requestWithdraw.", e);
        }
    }

    // If trade was completed (closed without fault but might be closed by a dispute) we move it to the closed trades
    public void onTradeCompleted(Trade trade) {
        removeTrade(trade);
        closedTradableManager.add(trade);

        // TODO The address entry should have been removed already. Check and if its the case remove that.
        btcWalletService.resetAddressEntriesForPendingTrade(trade.getId());
    }

    // If trade is in already in critical state (if taker role: taker fee; both roles: after deposit published)
    // we move the trade to failedTradesManager
    public void moveTradeToFailedTrades(Trade trade) {
        removeTrade(trade);
        failedTradesManager.add(trade);
    }

    public void addFailedTradeToPending(Trade trade) {
        if (!trade.isInitialized()) {
            initPendingTrade(trade);
        }
        tradableList.add(trade);
    }

    // If trade still has funds locked up it might come back from failed trades
    // Aborts unfailing if the address entries needed are not available
    private boolean unfailTrade(Trade trade) {
        if (!recoverAddresses(trade)) {
            log.warn("Failed to recover address during unfail trade");
            return false;
        }

        initPendingTrade(trade);

        if (!tradableList.contains(trade)) {
            tradableList.add(trade);
        }
        return true;
    }

    // The trade is added to pending trades if the associated address entries are AVAILABLE and
    // the relevant entries are changed, otherwise it's not added and no address entries are changed
    private boolean recoverAddresses(Trade trade) {
        // Find addresses associated with this trade.
        var entries = TradeUtils.getAvailableAddresses(trade, btcWalletService, keyRing);
        if (entries == null)
            return false;

        btcWalletService.recoverAddressEntry(trade.getId(), entries.first,
                AddressEntry.Context.MULTI_SIG);
        btcWalletService.recoverAddressEntry(trade.getId(), entries.second,
                AddressEntry.Context.TRADE_PAYOUT);
        return true;
    }

    private void removeTrade(Trade trade) {
        tradableList.remove(trade);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Dispute
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void closeDisputedTrade(String tradeId, Trade.DisputeState disputeState) {
        Optional<Trade> tradeOptional = getTradeById(tradeId);
        if (tradeOptional.isPresent()) {
            Trade trade = tradeOptional.get();
            trade.setDisputeState(disputeState);
            onTradeCompleted(trade);
            btcWalletService.swapTradeEntryToAvailableEntry(trade.getId(), AddressEntry.Context.TRADE_PAYOUT);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Publish delayed payout tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void publishDelayedPayoutTx(String tradeId,
                                       ResultHandler resultHandler,
                                       ErrorMessageHandler errorMessageHandler) {
        getTradeById(tradeId).ifPresent(trade -> {
            Transaction delayedPayoutTx = trade.getDelayedPayoutTx();
            if (delayedPayoutTx != null) {
                // We have spent the funds from the deposit tx with the delayedPayoutTx
                btcWalletService.swapTradeEntryToAvailableEntry(trade.getId(), AddressEntry.Context.MULTI_SIG);
                // We might receive funds on AddressEntry.Context.TRADE_PAYOUT so we don't swap that

                Transaction committedDelayedPayoutTx = WalletService.maybeAddSelfTxToWallet(delayedPayoutTx, btcWalletService.getWallet());

                tradeWalletService.broadcastTx(committedDelayedPayoutTx, new TxBroadcaster.Callback() {
                    @Override
                    public void onSuccess(Transaction transaction) {
                        log.info("publishDelayedPayoutTx onSuccess " + transaction);
                        NodeAddress tradingPeerNodeAddress = trade.getTradingPeerNodeAddress();
                        PeerPublishedDelayedPayoutTxMessage msg = new PeerPublishedDelayedPayoutTxMessage(UUID.randomUUID().toString(),
                                tradeId,
                                tradingPeerNodeAddress);
                        p2PService.sendEncryptedMailboxMessage(
                                tradingPeerNodeAddress,
                                trade.getProcessModel().getTradingPeer().getPubKeyRing(),
                                msg,
                                new SendMailboxMessageListener() {
                                    @Override
                                    public void onArrived() {
                                        resultHandler.handleResult();
                                        log.info("SendMailboxMessageListener onArrived tradeId={} at peer {}",
                                                tradeId, tradingPeerNodeAddress);
                                    }

                                    @Override
                                    public void onStoredInMailbox() {
                                        resultHandler.handleResult();
                                        log.info("SendMailboxMessageListener onStoredInMailbox tradeId={} at peer {}",
                                                tradeId, tradingPeerNodeAddress);
                                    }

                                    @Override
                                    public void onFault(String errorMessage) {
                                        log.error("SendMailboxMessageListener onFault tradeId={} at peer {}",
                                                tradeId, tradingPeerNodeAddress);
                                        errorMessageHandler.handleErrorMessage(errorMessage);
                                    }
                                }
                        );
                    }

                    @Override
                    public void onFailure(TxBroadcastException exception) {
                        log.error("publishDelayedPayoutTx onFailure", exception);
                        errorMessageHandler.handleErrorMessage(exception.toString());
                    }
                });
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ObservableList<Trade> getTradesAsObservableList() {
        return tradableList.getList();
    }

    public BooleanProperty pendingTradesInitializedProperty() {
        return pendingTradesInitialized;
    }

    public boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public boolean isBuyer(Offer offer) {
        // If I am the maker, we use the OfferPayload.Direction, otherwise the mirrored direction
        if (isMyOffer(offer))
            return offer.isBuyOffer();
        else
            return offer.getDirection() == OfferPayload.Direction.SELL;
    }

    public Optional<Trade> getTradeById(String tradeId) {
        return tradableList.stream().filter(e -> e.getId().equals(tradeId)).findFirst();
    }

    public Stream<AddressEntry> getAddressEntriesForAvailableBalanceStream() {
        Stream<AddressEntry> availableOrPayout = Stream.concat(btcWalletService.getAddressEntries(AddressEntry.Context.TRADE_PAYOUT)
                .stream(), btcWalletService.getFundedAvailableAddressEntries().stream());
        Stream<AddressEntry> available = Stream.concat(availableOrPayout,
                btcWalletService.getAddressEntries(AddressEntry.Context.ARBITRATOR).stream());
        available = Stream.concat(available, btcWalletService.getAddressEntries(AddressEntry.Context.OFFER_FUNDING).stream());
        return available.filter(addressEntry -> btcWalletService.getBalanceForAddress(addressEntry.getAddress()).isPositive());
    }

    public Stream<Trade> getTradesStreamWithFundsLockedIn() {
        return getTradesAsObservableList().stream()
                .filter(Trade::isFundsLockedIn);
    }

    public Set<String> getSetOfFailedOrClosedTradeIdsFromLockedInFunds() throws TradeTxException {
        AtomicReference<TradeTxException> tradeTxException = new AtomicReference<>();
        Set<String> tradesIdSet = getTradesStreamWithFundsLockedIn()
                .filter(Trade::hasFailed)
                .map(Trade::getId)
                .collect(Collectors.toSet());
        tradesIdSet.addAll(failedTradesManager.getTradesStreamWithFundsLockedIn()
                .filter(trade -> trade.getDepositTx() != null)
                .map(trade -> {
                    log.warn("We found a failed trade with locked up funds. " +
                            "That should never happen. trade ID=" + trade.getId());
                    return trade.getId();
                })
                .collect(Collectors.toSet()));
        tradesIdSet.addAll(closedTradableManager.getTradesStreamWithFundsLockedIn()
                .map(trade -> {
                    Transaction depositTx = trade.getDepositTx();
                    if (depositTx != null) {
                        TransactionConfidence confidence = btcWalletService.getConfidenceForTxId(depositTx.getTxId().toString());
                        if (confidence != null && confidence.getConfidenceType() != TransactionConfidence.ConfidenceType.BUILDING) {
                            tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithUnconfirmedDepositTx", trade.getShortId())));
                        } else {
                            log.warn("We found a closed trade with locked up funds. " +
                                    "That should never happen. trade ID=" + trade.getId());
                        }
                    } else {
                        tradeTxException.set(new TradeTxException(Res.get("error.closedTradeWithNoDepositTx", trade.getShortId())));
                    }
                    return trade.getId();
                })
                .collect(Collectors.toSet()));

        if (tradeTxException.get() != null)
            throw tradeTxException.get();

        return tradesIdSet;
    }

    public void applyTradePeriodState() {
        updateTradePeriodState();
        clockWatcher.addListener(new ClockWatcher.Listener() {
            @Override
            public void onSecondTick() {
            }

            @Override
            public void onMinuteTick() {
                updateTradePeriodState();
            }
        });
    }

    private void updateTradePeriodState() {
        getTradesAsObservableList().forEach(trade -> {
            if (!trade.isPayoutPublished()) {
                Date maxTradePeriodDate = trade.getMaxTradePeriodDate();
                Date halfTradePeriodDate = trade.getHalfTradePeriodDate();
                if (maxTradePeriodDate != null && halfTradePeriodDate != null) {
                    Date now = new Date();
                    if (now.after(maxTradePeriodDate))
                        trade.setTradePeriodState(Trade.TradePeriodState.TRADE_PERIOD_OVER);
                    else if (now.after(halfTradePeriodDate))
                        trade.setTradePeriodState(Trade.TradePeriodState.SECOND_HALF);
                }
            }
        });
    }

    // TODO Remove once tradableList is refactored to a final field
    //  (part of the persistence refactor PR)
    private void onTradesChanged() {
        this.numPendingTrades.set(getTradesAsObservableList().size());
    }

    public void persistTrades() {
        tradableList.persist();
    }

    public boolean wasOfferAlreadyUsedInTrade(String offerId) {
        return getTradeById(offerId).isPresent() ||
                failedTradesManager.getTradeById(offerId).isPresent() ||
                closedTradableManager.getTradableById(offerId).isPresent();
    }
}
