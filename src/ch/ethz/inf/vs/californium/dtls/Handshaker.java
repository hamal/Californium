/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/
package ch.ethz.inf.vs.californium.dtls;

import java.util.Collection;
import java.util.HashSet;
import java.util.logging.Logger;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import sun.security.internal.spec.TlsKeyMaterialParameterSpec;
import sun.security.internal.spec.TlsKeyMaterialSpec;
import sun.security.internal.spec.TlsMasterSecretParameterSpec;
import ch.ethz.inf.vs.californium.coap.EndpointAddress;
import ch.ethz.inf.vs.californium.coap.Message;
import ch.ethz.inf.vs.californium.dtls.CipherSuite.KeyExchangeAlgorithm;

@SuppressWarnings("deprecation")
public abstract class Handshaker {

	// Logging ////////////////////////////////////////////////////////

	protected static final Logger LOG = Logger.getLogger(Handshaker.class.getName());

	// Members ////////////////////////////////////////////////////////

	/**
	 * Indicates, whether the handshake protocol is performed from the client's
	 * side or the server's.
	 */
	protected boolean isClient;

	protected int state = -1;

	protected EndpointAddress endpointAddress;

	protected ProtocolVersion usedProtocol;
	protected Random clientRandom;
	protected Random serverRandom;
	protected CipherSuite cipherSuite;
	protected CompressionMethod compressionMethod;

	protected KeyExchangeAlgorithm keyExchange;

	private SecretKey masterSecret;

	private SecretKey clientWriteMACKey;
	private SecretKey serverWriteMACKey;

	private IvParameterSpec clientWriteIV;
	private IvParameterSpec serverWriteIV;

	private SecretKey clientWriteKey;
	private SecretKey serverWriteKey;

	protected DTLSSession session = null;

	/**
	 * The current sequence number (in the handshake message called message_seq)
	 * for this handshake.
	 */
	protected int sequenceNumber = 0;

	/** The next expected handshake message sequence number. */
	protected int nextReceiveSeq = 0;

	/** The CoAP {@link Message} that needs encryption. */
	protected Message message;

	/** Queue for messages, that can not yet be processed. */
	protected Collection<Record> queuedMessages;

	/**
	 * The last flight that is sent during this handshake, will not be
	 * retransmitted unless the peer retransmits its last flight.
	 */
	protected DTLSFlight lastFlight = null;
	
	// Constructor ////////////////////////////////////////////////////

	/**
	 * 
	 * @param peerAddress
	 * @param isClient
	 * @param session
	 */
	public Handshaker(EndpointAddress peerAddress, boolean isClient, DTLSSession session) {
		this.endpointAddress = peerAddress;
		this.isClient = isClient;
		this.session = session;
		queuedMessages = new HashSet<Record>();
	}
	
	// Abstract Methods ///////////////////////////////////////////////

	/**
	 * Processes the handshake message according to its {@link HandshakeType}
	 * and reacts according to the protocol specification.
	 * 
	 * @param message
	 *            the received {@link HandshakeMessage}.
	 * @return the list all handshake messages that need to be sent triggered by
	 *         this message.
	 */
	public abstract DTLSFlight processMessage(Record message);

	/**
	 * Gets the handshake flight which needs to be sent first to initiate
	 * handshake. This differs from client side to server side.
	 * 
	 * @return the handshake message to start off the handshake protocol.
	 */
	public abstract DTLSFlight getStartHandshakeMessage();
	
	// Methods ////////////////////////////////////////////////////////

	protected void generateKeys(SecretKey premasterSecret) {
		SecretKey masterSecret = generateMasterSecretKey(premasterSecret);

		try {
			int majorVersion = 3;
			int minorVersion = 2;
			// TODO get this from cipher suite
			// TODO deprecated
			TlsKeyMaterialParameterSpec keyMaterialParameterSpec = new TlsKeyMaterialParameterSpec(masterSecret, majorVersion, minorVersion, clientRandom.getRandomBytes(), serverRandom.getRandomBytes(), cipherSuite.getBulkCipher().toString(), 16, 8,
					4, 8, "SHA-256", 32, 64);
			KeyGenerator kg = KeyGenerator.getInstance("SunTlsKeyMaterial");
			kg.init(keyMaterialParameterSpec);
			TlsKeyMaterialSpec keySpec = (TlsKeyMaterialSpec) kg.generateKey();

			clientWriteKey = keySpec.getClientCipherKey();
			serverWriteKey = keySpec.getServerCipherKey();

			clientWriteIV = keySpec.getClientIv();
			serverWriteIV = keySpec.getServerIv();

			clientWriteMACKey = keySpec.getClientMacKey();
			serverWriteMACKey = keySpec.getServerMacKey();
		} catch (Exception e) {
			LOG.severe("Could not generate secret keys.");
			e.printStackTrace();
		}
	}

	private SecretKey generateMasterSecretKey(SecretKey premasterSecret) {
		try {
			KeyGenerator generator = KeyGenerator.getInstance("SunTlsMasterSecret");

			int majorVersion = 3;
			int minorVersion = 2;
			// TODO get this from cipher suite
			TlsMasterSecretParameterSpec spec = new TlsMasterSecretParameterSpec(premasterSecret, majorVersion, minorVersion, clientRandom.getRandomBytes(), serverRandom.getRandomBytes(), "SHA-256", 32, 64);
			generator.init(spec);
			masterSecret = generator.generateKey();

			return masterSecret;
		} catch (Exception e) {
			LOG.severe("Could not generate master secret.");
			e.printStackTrace();
			return null;
		}
	}

	protected void setCurrentReadState() {
		DTLSConnectionState connectionState;
		if (isClient) {
			connectionState = new DTLSConnectionState(cipherSuite, compressionMethod, serverWriteKey, serverWriteIV, serverWriteMACKey);
		} else {
			connectionState = new DTLSConnectionState(cipherSuite, compressionMethod, clientWriteKey, clientWriteIV, clientWriteMACKey);
		}
		session.setReadState(connectionState);
	}

	protected void setCurrentWriteState() {
		DTLSConnectionState connectionState;
		if (isClient) {
			connectionState = new DTLSConnectionState(cipherSuite, compressionMethod, clientWriteKey, clientWriteIV, clientWriteMACKey);
		} else {
			connectionState = new DTLSConnectionState(cipherSuite, compressionMethod, serverWriteKey, serverWriteIV, serverWriteMACKey);
		}
		session.setWriteState(connectionState);
	}

	/**
	 * Wraps the message into a record layer.
	 * 
	 * @param fragment
	 *            the {@link DTLSMessage} fragment.
	 * @return the fragment wrapped into a record layer.
	 */
	protected Record wrapMessage(DTLSMessage fragment) {

		ContentType type = null;
		if (fragment instanceof ApplicationMessage) {
			type = ContentType.APPLICATION_DATA;
		} else if (fragment instanceof AlertMessage) {
			type = ContentType.ALERT;
		} else if (fragment instanceof ChangeCipherSpecMessage) {
			type = ContentType.CHANGE_CIPHER_SPEC;
		} else if (fragment instanceof HandshakeMessage) {
			type = ContentType.HANDSHAKE;
		}

		return new Record(type, session.getWriteEpoch(), session.getSequenceNumber(), fragment, session);
	}

	/**
	 * Determines, using the epoch and sequence number, whether this record is
	 * the next one, which needs to be processed by the handshake protocol.
	 * 
	 * @param record
	 *            the current received message.
	 * @return <tt>true</tt> if the current message is the next to process,
	 *         <tt>false</tt> otherwise.
	 */
	protected boolean processMessageNext(Record record) {

		int epoch = record.getEpoch();
		if (epoch < session.getReadEpoch()) {
			// discard old message
			LOG.info("Discarded message due to older epoch.");
			return false;
		} else if (epoch == session.getReadEpoch()) {
			DTLSMessage fragment = record.getFragment();
			if (fragment instanceof AlertMessage) {
				return true; // Alerts must be processed immediately
			} else if (fragment instanceof ChangeCipherSpecMessage) {
				return true; // CCS must be processed immediately
			} else if (fragment instanceof HandshakeMessage) {
				int messageSeq = ((HandshakeMessage) fragment).getMessageSeq();

				if (messageSeq == nextReceiveSeq) {
					nextReceiveSeq++;
					return true;
				} else {
					return false;
				}
			} else {
				return false;
			}
		} else {
			// newer epoch, queue message
			queuedMessages.add(record);
			return false;
		}
	}

	// Getters and Setters ////////////////////////////////////////////

	public CipherSuite getCipherSuite() {
		return cipherSuite;
	}

	/**
	 * Sets the negotiated {@link CipherSuite} and the corresponding
	 * {@link KeyExchangeAlgorithm}.
	 * 
	 * @param cipherSuite
	 *            the cipher suite.
	 */
	public void setCipherSuite(CipherSuite cipherSuite) {
		this.cipherSuite = cipherSuite;
		this.keyExchange = cipherSuite.getKeyExchange();
	}

	public SecretKey getMasterSecret() {
		return masterSecret;
	}

	public SecretKey getClientWriteMACKey() {
		return clientWriteMACKey;
	}

	public SecretKey getServerWriteMACKey() {
		return serverWriteMACKey;
	}

	public IvParameterSpec getClientWriteIV() {
		return clientWriteIV;
	}

	public IvParameterSpec getServerWriteIV() {
		return serverWriteIV;
	}

	public SecretKey getClientWriteKey() {
		return clientWriteKey;
	}

	public SecretKey getServerWriteKey() {
		return serverWriteKey;
	}

	public DTLSSession getSession() {
		return session;
	}

	public void setSession(DTLSSession session) {
		this.session = session;
	}

	/**
	 * Add the smallest available message sequence to the handshake message.
	 * 
	 * @param message
	 *            the {@link HandshakeMessage}.
	 */
	public void setSequenceNumber(HandshakeMessage message) {
		message.setMessageSeq(sequenceNumber);
		sequenceNumber++;
	}

	public Message getMessage() {
		return message;
	}

	public void setMessage(Message message) {
		this.message = message;
	}

	public int getNextReceiveSeq() {
		return nextReceiveSeq;
	}

	public void incrementNextReceiveSeq(int nextReceiveSeq) {
		this.nextReceiveSeq++;
	}
}