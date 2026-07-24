package za.co.ntier.payment.form;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.logging.Level;

import org.adempiere.util.Callback;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.adempiere.webui.window.Dialog;
import org.adempiere.webui.window.SimplePDFViewer;
import org.compiere.model.MPaySelectionCheck;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.zkoss.zul.Filedownload;

@org.idempiere.ui.zk.annotation.Form(name = "org.compiere.apps.form.VPayPrint")
public class WPayPrint extends org.adempiere.webui.apps.form.WPayPrint {
	
	@Override
	protected void zkInit() throws Exception {
		super.zkInit();
		lDepositBatch.setText("Bank Batch");
		fDocumentNo.setReadWrite(false);
	}
	
	@Override
	protected void loadPaymentRule() {
		super.loadPaymentRule();
		bProcess.setEnabled(true);
	}
	
	@Override
	protected void getPluginFeatures() {
		super.getPluginFeatures();
		bPrint.setEnabled(true);
		if(fDepositBatch.isReadWrite()) {
			fDepositBatch.setValue(true);
		}
	}
	
	@Override
	protected void confirm_cmd_print() {
		String PaymentRule = fPaymentRule.getSelectedItem().toValueNamePair().getValue();
		if (!getChecks(PaymentRule))
			return;
		
		SimplePDFViewer remitViewer = null;
		
		List<File> pdfList = createRemittanceDocuments();

		try
		{
			File outFile = File.createTempFile("WPayPrint", null);
			AEnv.mergePdf(pdfList, outFile);
			String name = Msg.translate(Env.getCtx(), "Remittance");
			remitViewer = new SimplePDFViewer(name, new FileInputStream(outFile));
			remitViewer.setAttribute(Window.MODE_KEY, Window.MODE_EMBEDDED);
			ZKUpdateUtil.setWidth(remitViewer, "100%");
			dispose();
			SessionManager.getAppDesktop().showWindow(remitViewer);
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			Dialog.error(m_WindowNo, Msg.getMsg(Env.getCtx(), "ZZ_PrintRemittanceError"), e.getLocalizedMessage(), 
					new Callback<Integer>() {
						@Override
						public void onCallback(Integer result) {
							dispose();
						}
					}, Msg.getMsg(Env.getCtx(), "ZZ_PrintRemittanceErrorTitle"));
		}
	}
	
	@Override
	protected void cmd_EFT() {
		String PaymentRule = fPaymentRule.getSelectedItem().toValueNamePair().getValue();
		if (!getChecks(PaymentRule))
			return;

		try
		{
			// create payment (in case not yet create) and payment into batch
			MPaySelectionCheck.confirmPrint(m_checks, m_batch, (Boolean) fDepositBatch.getValue());
			
			Dialog.info(m_WindowNo, Msg.getMsg(Env.getCtx(), "ZZ_PaymentCreatedCompleted"), null, Msg.getMsg(Env.getCtx(), "PaymentCreated"), 
					new Callback<Integer>() {
						@Override
						public void onCallback(Integer result) {
							dispose();
						}
					});
		}catch (Exception e){
			log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			Dialog.error(m_WindowNo, Msg.getMsg(Env.getCtx(), "PaymentError"), e.getLocalizedMessage());
		}
	}
	
	@Override
	protected void cmd_export()
	{
		if (fPaymentRule.getSelectedItem() == null)
			return;
		String PaymentRule = fPaymentRule.getSelectedItem().toValueNamePair().getValue();
		if (!getChecks(PaymentRule))
			return;

		try
		{
			int no = 0;
			StringBuffer err = new StringBuffer("");
			if (m_PaymentExportClass == null || m_PaymentExportClass.trim().length() == 0)
			{
				m_PaymentExportClass = "org.compiere.util.GenericPaymentExport";
			}

			File tempFile = null;
			String filenameForDownload = "";

			no = loadPaymentExportClass(err);

			if (no >= 0)
			{
				// Get File Info
				tempFile = File.createTempFile(m_PaymentExport.getFilenamePrefix(), null);
				no = m_PaymentExport.exportToFile(m_checks, (Boolean) fDepositBatch.getValue(), PaymentRule, tempFile, err);

				// Append Payment Selection Name to the filename
				String paySelectionName = "";
				if (m_checks != null && m_checks.length > 0 && m_checks[0].getC_PaySelection() != null)
				{
					paySelectionName = "_" + m_checks[0].getC_PaySelection().getName();
				}

				filenameForDownload = m_PaymentExport.getFilenamePrefix() + paySelectionName + m_PaymentExport.getFilenameSuffix();
			}

			if (no >= 0)
			{
				Filedownload.save(new FileInputStream(tempFile), m_PaymentExport.getContentType(), filenameForDownload);
				Dialog.info(m_WindowNo, "Saved",
							Msg.getMsg(Env.getCtx(), "NoOfLines") + "=" + no);

				Dialog.ask(m_WindowNo, "VPayPrintSuccess?", new Callback<Boolean>() {
					@Override
					public void onCallback(Boolean result)
					{
						if (result)
						{
							MPaySelectionCheck.confirmPrint(m_checks, m_batch, (Boolean) fDepositBatch.getValue());
							// document No not updated
						}
					}
				});
			}
			else
			{
				Dialog.error(m_WindowNo, "Error", err.toString());
			}
			dispose();
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, e.getLocalizedMessage(), e);
		}
	}
}
