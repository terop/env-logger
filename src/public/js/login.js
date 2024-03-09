/* global alert,axios */

let username = '';

const publicKeyCredentialRequestOptions = function (server) {
  const credentials = [];
  for (const cred of server.credentials) {
    credentials.push({
      id: Uint8Array.from(
        atob(cred.id),
        c => c.charCodeAt(0)),
      type: cred.type,
      transports: ['internal', 'usb']
    });
  }

  return {
    challenge: Uint8Array.from(
      server.challenge, c => c.charCodeAt(0)),
    allowCredentials: credentials,
    userVerification: 'discouraged',
    timeout: 60000
  };
};

const doWebAuthnLogin = function (resolve, reject) {
  axios.get('webauthn/login',
    {
      params: {
        username
      }
    })
    .then(resp => {
      return resp.data;
    })
    .catch(error => {
      console.log(`Prepare login error: ${error}`);
    })
    .then(async resp => {
      const pubKey = publicKeyCredentialRequestOptions(resp);
      const assertion = await navigator.credentials.get({ publicKey: pubKey });
      return {
        challenge: resp.challenge,
        'credential-id': btoa(String.fromCharCode(...new Uint8Array(assertion.rawId))),
        'user-handle': btoa(username),
        'authenticator-data':
          btoa(String.fromCharCode(...new Uint8Array(assertion.response.authenticatorData))),
        signature: btoa(String.fromCharCode(...new Uint8Array(assertion.response.signature))),
        attestation: btoa(String.fromCharCode(...new Uint8Array(assertion.response.attestationObject))),
        'client-data': btoa(String.fromCharCode(...new Uint8Array(assertion.response.clientDataJSON)))
      };
    })
    .catch(_ => {})
    .then(payload => {
      axios.post('webauthn/login',
        payload)
        .then(resp => {
          resolve({
            result: true,
            'redirect-url': resp.data
          });
        })
        .catch(error => {
          if (error.response.data && error.response.data.error &&
                        error.response.data.error === 'invalid-authenticator') {
            reject({
              result: false,
              cause: 'invalid-authenticator'
            });
          }

          reject({
            result: false,
            cause: error
          });
        });
    });
};

async function handleWebAuthnLogin () {
  if (!username) {
    alert('Error: username cannot be empty');
    return;
  }

  new Promise(doWebAuthnLogin)
    .then(result => {
      window.location = result['redirect-url'];
    })
    .catch(error => {
      console.log(`Login error: ${error.cause}`);

      document.getElementById('errorBox').innerHTML = 'WebAuthn login failed';
      document.getElementById('errorBox').classList.remove('hidden');
    });
}

const handleUsernameBlur = function () {
  username = document.getElementById('username').value;
};

document.getElementById('webAuthnLogin').addEventListener('click', handleWebAuthnLogin);
document.getElementById('username').addEventListener('blur', handleUsernameBlur);
