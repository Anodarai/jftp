/*
 * Copyright 2012 jMethods, Inc. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.myjavaworld.jftp.ssl;

import java.awt.EventQueue;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.myjavaworld.jftp.JFTP;

/**
 * @author Sai Pullabhotla, psai [at] jMethods [dot] com
 * 
 *         To change the template for this generated type comment go to
 *         Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
public class JFTPTrustManager implements X509TrustManager {

	private JFTP jftp = null;
	private String hostName = null;
	private X509TrustManager tm = null;
	private X509Certificate[] chain = null;
	private final BooleanSupplier live;

	/**
	 * Creates a new instance of <code>JFTPTrustManager</code>.
	 * 
	 * @param jftp
	 *            JFTP instance
	 * @param hostName
	 *            Host name against which the host name verification is to be
	 *            done.
	 * 
	 * @throws KeyStoreException
	 * @throws NoSuchAlgorithmException
	 * 
	 */
	public JFTPTrustManager(JFTP jftp, String hostName, BooleanSupplier live)
			throws KeyStoreException, NoSuchAlgorithmException {
		super();
		this.jftp = jftp;
		this.hostName = hostName;
		this.live = live;
		TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
		tmf.init(KeyStoreManager.getServerCertificateStore());
		tm = (X509TrustManager) tmf.getTrustManagers()[0];
	}

	public X509Certificate[] getAcceptedIssuers() {
		return tm.getAcceptedIssuers();
	}

	public void checkClientTrusted(X509Certificate[] chain, String authType)
			throws CertificateException {
		tm.checkClientTrusted(chain, authType);
	}

	public void checkServerTrusted(X509Certificate[] chain, String authType)
			throws CertificateException {

		if (this.chain != null) {
			if (Arrays.equals(chain, this.chain)) {
				return;
			}
		}

		boolean validDate = isValidDate(chain);
		boolean validHost = isValidHost(chain);
		boolean trusted = isTrusted(chain);

		if (!validDate || !validHost || !trusted) {
			int userOption = requestDecision(chain,
					validDate, validHost, trusted);
			if (userOption != SecurityWarningDlg.YES_OPTION) {
				throw new CertificateException("No trusted certificate found. ");
			}
			this.chain = chain;
		} else {
			this.chain = chain;
		}
	}

	private int requestDecision(X509Certificate[] chain, boolean validDate, boolean validHost, boolean trusted)
			throws CertificateException {
		FutureTask<Integer> decision = new FutureTask<>(() -> live.getAsBoolean()
				? SecurityWarningDlg.showDialog(jftp, chain, validDate, validHost, trusted)
				: SecurityWarningDlg.NO_OPTION);
		if (EventQueue.isDispatchThread()) {
			decision.run();
		} else {
			EventQueue.invokeLater(decision);
		}
		try {
			return decision.get();
		} catch (InterruptedException interrupted) {
			decision.cancel(false);
			Thread.currentThread().interrupt();
			throw new CertificateException("Certificate decision interrupted", interrupted);
		} catch (ExecutionException failed) {
			throw new CertificateException("Certificate decision failed", failed.getCause());
		}
	}

	/**
	 * Checks to see if the certificate date is valid. This method only checks
	 * the first certificate in the chain.
	 * 
	 * @param chain
	 * @return <code>true</code> if the date on the certificate is valid.
	 *         <code>false</code>, otherwise.
	 * 
	 */
	private boolean isValidDate(X509Certificate[] chain) {
		try {
			chain[0].checkValidity();
		} catch (CertificateNotYetValidException exp) {
			return false;
		} catch (CertificateException exp) {
			return false;
		}
		return true;
	}

	/**
	 * Checks the host name on the certificate with the currently connecting
	 * host name.
	 * 
	 * @param chain
	 * @return <code>true</code>, if the host name verification succeeds.
	 *         <code>false</code>, otherwise.
	 * 
	 */
	private boolean isValidHost(X509Certificate[] chain) {
		X509Certificate certificate = chain[0];
		String subjectDN = certificate.getSubjectDN().getName();
		DNParser parser = new DNParser(subjectDN);
		String commonName = parser.getParameter("CN");
		if (commonName != null) {
			return commonName.toUpperCase().equals(hostName.toUpperCase());
		}
		return false;
	}

	/**
	 * Checks if the given certificate chain can be trusted.
	 * 
	 * @param chain
	 * @return <code>true</code>, if the certificate chain is trusted.
	 *         <code>false</code>, otherwise.
	 * 
	 */
	private boolean isTrusted(X509Certificate[] chain) {
		boolean trusted = false;
		try {
			KeyStore keyStore = KeyStoreManager.getServerCertificateStore();
			for (int i = chain.length - 1; i >= 0; i--) {
				if (keyStore.getCertificateAlias(chain[i]) != null) {
					trusted = true;
					break;
				}
			}
		} catch (Exception e) {
			trusted = false;
		}
		return trusted;
	}
}
