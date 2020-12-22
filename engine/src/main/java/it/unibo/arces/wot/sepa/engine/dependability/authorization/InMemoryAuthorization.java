/* The class implements a in-memory (volatile) authorization
 * 
 * Author: Luca Roffia (luca.roffia@unibo.it)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package it.unibo.arces.wot.sepa.engine.dependability.authorization;

import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import it.unibo.arces.wot.sepa.commons.exceptions.SEPASecurityException;
import it.unibo.arces.wot.sepa.commons.security.Credentials;
import it.unibo.arces.wot.sepa.engine.dependability.authorization.identities.ApplicationIdentity;
import it.unibo.arces.wot.sepa.engine.dependability.authorization.identities.DeviceIdentity;
import it.unibo.arces.wot.sepa.engine.dependability.authorization.identities.DigitalIdentity;

public class InMemoryAuthorization implements IAuthorization {
	private static final Logger logger = LogManager.getLogger();
	
	class AuthorizedIdentity {
		DigitalIdentity identity = null;
		String secret = null;
		String user = null;
		SignedJWT token = null;
		Long expiring = null;
		boolean authorized = true;
		
		public AuthorizedIdentity(DigitalIdentity identity) {
			this.identity = identity;
			
			// TODO: WARNING!!! JUST FOR TESTING
			if (identity.getUid().equals("SEPATest")) {
				expiring= SEPATestExpiringTime;
			}
			else if (identity.getClass().equals(DeviceIdentity.class)) {
				expiring = deviceExpiringTime;
			} else if (identity.getClass().equals(ApplicationIdentity.class)) { 
				expiring = applicationExpiringTime;
			} else 
				expiring = defaultExpiringTime;
		}
		
		public void register(String user,String secret) {
			this.secret = secret;
			this.user = user;
		}
		
		public void unregister() {
			this.secret = null;
			this.user = null;
		}

		public boolean isRegistered() {
			return (user != null && secret != null);
		}

		public boolean checkPassword(String pwd) {
			return pwd.equals(secret);
		}
		
		public boolean containsToken() {
			return token != null;
		}
		
		public void removeToken() {
			token = null;
		}

		public Date getTokenExpiringDate() throws SEPASecurityException {
			JWTClaimsSet claims = null;
			try {
				claims = token.getJWTClaimsSet();
			} catch (ParseException e) {
				throw new SEPASecurityException(e);
			}
			
			return claims.getExpirationTime();
		}

		public void addToken(SignedJWT jwt) throws SEPASecurityException {
			token = jwt;
			
			JWTClaimsSet claims = null;
			try {
				claims = jwt.getJWTClaimsSet();
			} catch (ParseException e) {
				throw new SEPASecurityException(e);
			}
			
			expiring = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())/1000;
		}

		public long getExpiringPeriod() {
			return expiring;
		}

		public SignedJWT getToken() {
			return token;
		}

		public DigitalIdentity getIdentity() {
			return identity;
		}

		public void setExpiringPeriod(long period) {
			expiring = period;	
		}

		public boolean isAuthorized() {
			return authorized;
		}

		public void removeIdentity() {
			authorized = false;
		}
	}
	
	private final HashMap<String,AuthorizedIdentity> identities = new HashMap<String,AuthorizedIdentity>();

	private String issuer = "https://localhost:8443/oauth/token";
//	private String httpsAudience = "https://wot.arces.unibo.it:8443/sparql";
//	private String wssAudience = "wss://wot.arces.unibo.it:9443/sparql";
//	private String subject = "SEPATest";

	private long deviceExpiringTime = 3600;
	private long applicationExpiringTime = 43200;
	private long userExpiringTime = 300;
	private long defaultExpiringTime = 5;
	private long SEPATestExpiringTime = 5;
	
	public InMemoryAuthorization() {
		// TODO: WARNING!!! JUST FOR TESTING
		addAuthorizedIdentity(new ApplicationIdentity("SEPATest",new Credentials("SEPATest","SEPATest")));
	}
	
	@Override
	public boolean isAuthorized(String uid) throws SEPASecurityException {
		logger.debug("isAuthorized "+uid);
		
		if (identities.containsKey(uid)) return identities.get(uid).isAuthorized();
		
		return false;
	}
	
	@Override
	public void addAuthorizedIdentity(DigitalIdentity identity) {
		logger.debug("addIdentity "+identity.getUid());
		
		identities.put(identity.getUid(),new AuthorizedIdentity(identity));
	}
	@Override
	public void removeAuthorizedIdentity(String uid) {
		logger.debug("removeIdentity "+uid);
		
		if (uid.equals("SEPATest")) return;
		
		if (identities.containsKey(uid)) identities.get(uid).removeIdentity();
	}
	
	// Client credentials
	@Override
	public boolean storeCredentials(DigitalIdentity identity, String client_secret) {
		logger.debug("storeCredentials "+identity.getUid()+" : "+client_secret);
		
		identities.put(identity.getUid(), new AuthorizedIdentity(identity));
		identities.get(identity.getUid()).register(identity.getUid(),client_secret);
		
		return true;
	}
	
	@Override
	public void removeCredentials(DigitalIdentity identity) throws SEPASecurityException {
		logger.debug("removeCredentials "+identity.getUid());
		
		if (identities.containsKey(identity.getUid())) identities.get(identity.getUid()).unregister();
	}
	@Override
	public boolean containsCredentials(String id) {
		logger.debug("containsCredentials "+id);
		
		if (identities.containsKey(id)) return identities.get(id).isRegistered();
		
		return false;
	}
	
	@Override
	public boolean checkCredentials(String id, String secret) {
		logger.debug("checkCredentials "+id+" : "+secret);
		
		if (identities.containsKey(id)) return identities.get(id).checkPassword(secret);
		
		return false;
	}
	
	// Client claims
	@Override
	public boolean containsToken(String id) {
		logger.debug("containsToken "+id);
		
		if (identities.containsKey(id)) return identities.get(id).containsToken();
		
		return false;
	}
	
	@Override
	public Date getTokenExpiringDate(String id) throws SEPASecurityException {
		logger.debug("getTokenExpiringDate "+id);
		
		if (identities.containsKey(id)) return identities.get(id).getTokenExpiringDate();
		
		return null;
	}
	
	@Override
	public void addToken(String id, SignedJWT jwt) throws SEPASecurityException {
		logger.debug("addToken "+id+" "+jwt.serialize());
		
		if (identities.containsKey(id)) identities.get(id).addToken(jwt);
	}
	
	@Override
	public void removeToken(String id) throws SEPASecurityException {
		logger.debug("removeToken "+id);
		
		if (identities.containsKey(id)) identities.get(id).removeToken();
	}

	// JWT
	@Override
	public long getTokenExpiringPeriod(String id) throws SEPASecurityException {
		logger.debug("getTokenExpiringPeriod "+id);
		
		if (identities.containsKey(id)) return identities.get(id).getExpiringPeriod();
		
		return -1;
	}
	
	@Override
	public void setTokenExpiringPeriod(String id, long period) {
		logger.debug("setTokenExpiringPeriod "+id+" : "+period);
		
		if (identities.containsKey(id)) identities.get(id).setExpiringPeriod(period);
	}
	
	@Override
	public SignedJWT getToken(String id) {
		logger.debug("getToken "+id);
		
		if (identities.containsKey(id)) return identities.get(id).getToken();
		
		return null;
	}
	@Override
	public DigitalIdentity getIdentity(String uid) throws SEPASecurityException {
		logger.debug("getIdentity "+uid);
		
		if (identities.containsKey(uid)) return identities.get(uid).getIdentity();
		
		return null;
	}
	
	@Override
	public Credentials getEndpointCredentials(String uid) throws SEPASecurityException {
		logger.debug("getEndpointCredentials "+uid);
		
		if (identities.containsKey(uid)) return identities.get(uid).getIdentity().getEndpointCredentials();	
		
		return null;
	}
	
	@Override
	public String getIssuer() {
		return issuer;
	}
	@Override
	public void setIssuer(String is) {
		issuer = is;
	}

	@Override
	public void setDeviceExpiringPeriod(long period) throws SEPASecurityException {
		deviceExpiringTime = period;
	}
	@Override
	public long getDeviceExpiringPeriod() throws SEPASecurityException {
		return deviceExpiringTime;
	}
	@Override
	public void setApplicationExpiringPeriod(long period) throws SEPASecurityException {
		applicationExpiringTime = period;
	}
	@Override
	public long getApplicationExpiringPeriod() throws SEPASecurityException {
		return applicationExpiringTime;
	}
	@Override
	public void setUserExpiringPeriod(long period) throws SEPASecurityException {
		userExpiringTime = period;
	}
	@Override
	public long getUserExpiringPeriod() throws SEPASecurityException {
		return userExpiringTime;
	}
	@Override
	public void setDefaultExpiringPeriod(long period) throws SEPASecurityException {
		defaultExpiringTime = period;
	}
	@Override
	public long getDefaultExpiringPeriod() throws SEPASecurityException {
		return defaultExpiringTime;
	}

	@Override
	public boolean isForTesting(String identity) throws SEPASecurityException {
		return identity.equals("SEPATest");
	}
}