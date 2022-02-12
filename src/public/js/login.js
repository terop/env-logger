const publicKeyCredentialRequestOptions = (server) => ({
    challenge: Uint8Array.from(
        server.challenge, c => c.charCodeAt(0)),
    allowCredentials: [{
        id: Uint8Array.from(
            atob(server.credentials[0].id),
            c => c.charCodeAt(0)),
        type: server.credentials[0].type,
        transports: ['internal', 'usb'],
    }],
    userVerification: 'discouraged',
    timeout: 60000,
});

const doWebAuthnLogin = function (resolve, reject) {
    axios.get('webauthn/login',
              {
                  params: {
                      username: username
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
            const assertion = await navigator.credentials.get({publicKey: pubKey});
            return {
                'challenge': resp.challenge,
                'credential-id': btoa(String.fromCharCode(...new Uint8Array(assertion.rawId))),
                'user-handle': btoa(username),
                'authenticator-data': btoa(String.fromCharCode(...new Uint8Array(assertion.response.authenticatorData))),
                'signature': btoa(String.fromCharCode(...new Uint8Array(assertion.response.signature))),
                'attestation': btoa(String.fromCharCode(...new Uint8Array(assertion.response.attestationObject))),
                'client-data': btoa(String.fromCharCode(...new Uint8Array(assertion.response.clientDataJSON))),
            };
        })
        .catch(error => {})
        .then(payload => {
            axios.post('webauthn/login',
                       payload)
                .then(resp => {
                    resolve({'result': true,
                             'redirect-url': resp.data});
                })
                .catch(error => {
                    if (error.response.data && error.response.data.error &&
                        error.response.data.error === 'invalid-authenticator')
                        reject({'result': false,
                                'cause': 'invalid-authenticator'});

                    reject({'result': false,
                            'cause': error});
                });
        });
};

async function handleWebAuthnLogin() {
    var success = false;

    if (!username) {
        alert('Error: username cannot be empty');
        return;
    }

    const callLoginPromise = function () {
        return new Promise(doWebAuthnLogin)
            .then(result => {
                return result;
            })
            .catch(error => {
                if (error['cause'] !== 'invalid-authenticator') {
                    console.log(`Login error: ${error['cause']}`);

                    return {'result': false,
                            'cause': 'other'};
                } else {
                    return error;
                }
            });
    };

    for (; attemptCount < authenticatorCount; attemptCount++) {
        if (success)
            break;
        var resp = await callLoginPromise();
        if (resp['result']) {
            success = true;
            window.location = resp['redirect-url'];
        }
    }
    if (!success) {
        document.getElementById('errorBox').innerHTML = 'WebAuthn login failed';
        document.getElementById('errorBox').classList.remove('hidden');
    }
}

const handleUsernameBlur = function () {
    username = document.getElementById('username').value;
    if (!username || username.length < 3)
        return;

    axios.get('webauthn/auth-count',
              {
                  params: {
                      username: username
                  }
              })
        .then(resp => {
            authenticatorCount = resp.data;
        })
        .catch(error => {
            console.log(`Authenticator count fetch error: ${error}`);
        });
};

document.getElementById('webAuthnLogin').addEventListener('click', handleWebAuthnLogin);
document.getElementById('username').addEventListener('blur', handleUsernameBlur);
