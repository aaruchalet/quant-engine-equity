package com.aaruchalet.quant.backtest;

/** Estimated NSE equity intraday charges for one executed order; rates are percentages from YAML. */
final class AngelOneIntradayFees {
    private AngelOneIntradayFees() {}

    static double orderFee(double notional, boolean buy, VwapEmaV2Config.FeeConfig rates) {
        double brokerage=Math.min(rates.maximumBrokerageInr(),
                Math.max(rates.minimumBrokerageInr(),notional*rates.brokeragePercent()/100));
        double exchange=notional*rates.exchangeTransactionPercent()/100;
        double ipft=notional*rates.investorProtectionPercent()/100;
        double sebi=notional*rates.sebiTurnoverPercent()/100;
        double gst=(brokerage+exchange+ipft+sebi)*rates.gstPercent()/100;
        double tax=notional*(buy ? rates.stampDutyBuyPercent() : rates.sttSellPercent())/100;
        return brokerage+exchange+ipft+sebi+gst+tax;
    }
}
