package za.co.ntier.payment.process;

import static org.compiere.model.SystemIDs.FORM_PAYMENT_PRINT_EXPORT;
import static org.compiere.model.SystemIDs.PROCESS_C_PAYSELECTION_CREATEPAYMENT;
import static org.compiere.model.SystemIDs.REFERENCE_PAYMENTRULE;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.base.annotation.Parameter;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.IProcessUI;
import org.adempiere.util.ProcessUtil;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.apps.form.WPayPrint;
import org.adempiere.webui.panel.ADForm;
import org.adempiere.webui.session.SessionManager;
import org.compiere.model.I_C_CommissionDetail;
import org.compiere.model.I_C_InvoicePaySchedule;
import org.compiere.model.I_C_PaySelectionLine;
import org.compiere.model.I_I_BankStatement;
import org.compiere.model.MPInstance;
import org.compiere.model.MPaySelection;
import org.compiere.model.MPaySelectionLine;
import org.compiere.model.MProcess;
import org.compiere.model.MRefList;
import org.compiere.model.X_C_Order;
import org.compiere.model.X_C_PaySelection;
import org.compiere.process.ProcessInfo;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Trx;
import org.compiere.util.Util;

import za.co.ntier.api.util.NtierProcessUtil;
import za.co.ntier.payment.event.delegate.LoginEventDelegate;

@org.adempiere.base.annotation.Process
public class PaymentSelectionManualProcess extends SvrProcess {

	@Parameter(name="pC_BankAccount_ID")
	int bankAccountID;

	@Parameter(name="pPayDate")
	Timestamp payDate;

	@Parameter(name="pC_DocType_ID")
	int docTypeID;

	@Parameter(name="pPaymentRule")
	String paymentRule;

	@Parameter(name="pOnlyDue")
	boolean isOnlyDue;

	@Parameter(name="pPositiveBalance")
	boolean isOnlyPositiveBalance;

	@Parameter(name="pIsOnePaymentPerInvoice")
	boolean isOnePaymentPerInvoice;

	@Parameter(name="pC_BPartner_ID")
	int bPartnerID;
	
	@Parameter(name="pisGeneratePayment")
	boolean isGeneratePayment = false;
	
	@Parameter(name="pisOpenPrintPayment")
	boolean isOpenPrintPayment = false;
	
	@Parameter(name="pName")
	String paymentSelectionName = null;
	
	@Parameter(name="pReference")
	String reference = null;
	
	protected MPaySelection m_ps;

	@Override
	protected String doIt() throws Exception {

		Map<String, Map<Object, Object>> selectedRecordsMap = NtierProcessUtil.getSelectedRecordsFromTempTable(get_TrxName(), getAD_PInstance_ID());
		m_ps = PaymentSelectionManualProcess.generatePaySelect(
						selectedRecordsMap, paymentSelectionName, reference, 
						paymentRule, payDate, bankAccountID, isOnePaymentPerInvoice, 
						get_TrxName(), log);
		
		addBufferLog(0, null, null, 
				Msg.getMsg(getCtx(), "GeneratedPaymentSelection", new Object [] {m_ps.getName()}), 
				m_ps.get_Table_ID(), m_ps.getC_PaySelection_ID());
		if ("N".equals(Env.getContext(Env.getCtx(), LoginEventDelegate.isAskInvokeGeneratePayment))) {
			// do on post process
		} else if (isGeneratePayment) {
			Map.Entry<Boolean, ProcessInfo> runResult = PaymentSelectionManualProcess.runProcessGeneratePayment(isOnePaymentPerInvoice, 
					getCtx(), get_TrxName(), getProcessInfo().getProcessUI(), m_ps.getC_PaySelection_ID());
			if (!runResult.getKey()) {
				addLog(runResult.getValue().getSummary());
				return "@Error@";
			}
		}
		
		return null;
	}
	
	@Override
	protected void postProcess(boolean success) {
		super.postProcess(success);
		if (!success)
			return;
		
		Runnable runnableOpenWindow = null;
		
		if ("N".equals(Env.getContext(Env.getCtx(), LoginEventDelegate.isAskInvokeGeneratePayment))) {
			runnableOpenWindow = new Runnable() {
				@Override
				public void run() {
					AEnv.zoom(MPaySelection.Table_ID, m_ps.getC_PaySelection_ID());
				}
			};
		}else if(isGeneratePayment && isOpenPrintPayment){
			runnableOpenWindow = new Runnable() {
				@Override
				public void run() {
					int AD_Form_ID = FORM_PAYMENT_PRINT_EXPORT;	//	Payment Print/Export
					ADForm form = SessionManager.getAppDesktop().openForm(AD_Form_ID);
					if (m_ps != null){
						WPayPrint pp = (WPayPrint) form.getICustomForm();
						pp.setPaySelection(m_ps.getC_PaySelection_ID());
					}
				}
			};
		}
		
		if (runnableOpenWindow != null)
			AEnv.executeAsyncDesktopTask(runnableOpenWindow);
	}
	
	public static Map.Entry<Boolean, ProcessInfo> runProcessGeneratePayment(boolean isOnePaymentPerInvoice, Properties ctx, String trxName, IProcessUI processUI, int paymentSelectionID) {
		//	Execute Process PaySelectionCreateCheck
		MProcess proc = new MProcess(ctx, PROCESS_C_PAYSELECTION_CREATEPAYMENT, null);
		ProcessInfo pi = new ProcessInfo(proc.getName(), PROCESS_C_PAYSELECTION_CREATEPAYMENT,
				X_C_PaySelection.Table_ID, paymentSelectionID);
		pi.setClassName(proc.getClassname());
		
		MPInstance instance = new MPInstance(Env.getCtx(), pi.getAD_Process_ID(), 0, 0, null);
		instance.saveEx();
		pi.setAD_PInstance_ID(instance.getAD_PInstance_ID()); // Create a new process instance
		
		ProcessInfoParameter piParam = new ProcessInfoParameter(MPaySelection.COLUMNNAME_IsOnePaymentPerInvoice, isOnePaymentPerInvoice, "", "", "");
		pi.setParameter(new ProcessInfoParameter[] {piParam});
		
		pi.setAD_User_ID(Env.getAD_User_ID(ctx));
		pi.setAD_Client_ID(Env.getAD_Client_ID(ctx));
		
		boolean runResult = ProcessUtil.startJavaProcess(ctx, pi, Trx.get(trxName, false), false, processUI);
		return new AbstractMap.SimpleEntry<Boolean, ProcessInfo>(runResult, pi);

	}
	
	/**
	 * convert from PaySelect.generatePaySelect
	 */
	public static MPaySelection generatePaySelect(Map<String, Map<Object, Object>> selectedRecordsMap,
			String paymentSelectionName, String reference,
			String paymentRule, Timestamp payDate, int bankAccountId, boolean isOnePaymentPerInvoice, 
			String trxName, CLogger	log) {
		Trx trx = null;
		MPaySelection m_ps = null;
		try {
			if (Util.isEmpty(trxName)) {
				trxName = Trx.createTrxName("PaySelect");
				trx = Trx.get(trxName, true);
				trx.setDisplayName(PaymentSelectionManualProcess.class.getName()+"_generatePaySelect");
			}
			//  Create Header
			m_ps = new MPaySelection(Env.getCtx(), 0, trxName);
			MRefList paymentRuleRef = MRefList.get(Env.getCtx(), REFERENCE_PAYMENTRULE, paymentRule, null);
			
			if (Util.isEmpty(paymentSelectionName))
				paymentSelectionName = Msg.getMsg(Env.getCtx(), "VPaySelect")
						+ " - " + paymentRuleRef.getName()
						+ " - " + payDate;
			m_ps.setName (paymentSelectionName);
			m_ps.set_ValueOfColumn(I_C_CommissionDetail.COLUMNNAME_Reference, reference);
			
			m_ps.setPayDate (payDate);
			m_ps.setC_BankAccount_ID(bankAccountId);
			m_ps.setIsApproved(true);
			m_ps.setIsOnePaymentPerInvoice(isOnePaymentPerInvoice);
			m_ps.saveEx();
			if (log.isLoggable(Level.CONFIG)) log.config(m_ps.toString());
			
			int line = 0;
			//  Create Lines
			for(Map.Entry<String, Map<Object, Object>> selectedRecordsEntry : selectedRecordsMap.entrySet()) {
				line += 10;
				Map<Object, Object> selectedRow = selectedRecordsEntry.getValue();
				MPaySelectionLine psl = new MPaySelectionLine (m_ps, line, paymentRule);
				int C_Invoice_ID = (int)selectedRow.get(NtierProcessUtil.TSelectionInfoWindowColumn.ID);
				BigDecimal OpenAmt = (BigDecimal)selectedRow.get(I_C_InvoicePaySchedule.COLUMNNAME_DueAmt);//9
				BigDecimal DiscountAmt = (BigDecimal)selectedRow.get(I_C_PaySelectionLine.COLUMNNAME_DiscountAmt);//6
				BigDecimal WriteOffAmt = (BigDecimal)selectedRow.get(I_C_PaySelectionLine.COLUMNNAME_WriteOffAmt);//7
				BigDecimal PayAmt = (BigDecimal)selectedRow.get(I_C_PaySelectionLine.COLUMNNAME_PayAmt);//10
				boolean isSOTrx = X_C_Order.PAYMENTRULE_DirectDebit.equals(paymentRule);
				
				psl.setPayAmt(PayAmt);
				//
				psl.setInvoice(C_Invoice_ID, isSOTrx,
					OpenAmt, PayAmt, DiscountAmt, WriteOffAmt);
				psl.set_ValueOfColumn(I_C_CommissionDetail.COLUMNNAME_Reference, selectedRow.get(I_I_BankStatement.COLUMNNAME_ReferenceNo));
				psl.saveEx(trxName);
				if (log.isLoggable(Level.FINE)) log.fine("C_Invoice_ID=" + C_Invoice_ID + ", PayAmt=" + PayAmt);
            }
			
			return m_ps;
		} catch (Exception e) {
			if (trx != null) {
				trx.rollback();
				trx.close();
			}
			throw new AdempiereException(e);
		} finally {
			if (trx != null && trx.isActive()) {
				m_ps.set_TrxName(null);
				trx.commit();
				trx.close();
			}
		}
	}
	
	@Override
	protected void prepare() {
		// TODO Auto-generated method stub
		
	}
}
