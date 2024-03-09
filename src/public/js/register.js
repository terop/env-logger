/* global axios,username */

const publicKeyCredentialCreationOptions = (server, username) => ({
  challenge: Uint8Array.from(
    server.challenge, c => c.charCodeAt(0)),
  rp: {
    name: server.rp.name,
    id: server.rp.id
  },
  user: {
    id: Uint8Array.from(
      server.user.id, c => c.charCodeAt(0)),
    displayName: username,
    name: username
  },
  pubKeyCredParams: server.cred,
  authenticatorSelection: {
    authenticatorAttachment: 'cross-platform',
    userVerification: 'discouraged'
  },
  timeout: 60000,
  attestation: 'direct'
});

const handleRegistration = function () {
  axios.get('webauthn/register',
    {
      params: {
        username,
        name: document.getElementById('name').value
      }
    })
    .then(resp => {
      return resp.data;
    })
    .then(async resp => {
      const pubKey = publicKeyCredentialCreationOptions(resp, username);
      const creds = await navigator.credentials.create({ publicKey: pubKey });
      return {
        challenge: resp.challenge,
        attestation: btoa(String.fromCharCode(...new Uint8Array(creds.response.attestationObject))),
        'client-data': btoa(String.fromCharCode(...new Uint8Array(creds.response.clientDataJSON)))
      };
    })
    .then(payload => {
      axios.post('webauthn/register',
        payload)
        .then(_ => {
          document.getElementById('registerSuccess').classList.remove('hidden');
        })
        .catch(_ => {
          document.getElementById('registerFailure').classList.remove('hidden');
        });
    });
};

document.getElementById('register').addEventListener('click', handleRegistration);
