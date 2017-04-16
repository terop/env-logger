
// Shows or hides an element with the given ID. The default action is to show.
var changeVisibility = function (id, hide) {
    if (hide && hide === true) {
        document.getElementById(id).classList.add('displayNone');
    } else {
        document.getElementById(id).classList.remove('displayNone');
    }
};

document.getElementById('showProfiles')
    .addEventListener('click',
                      function () {
                          document.getElementById('profilesDiv').classList.toggle('displayNone');
                      },
                      false);

// Hides all data series
var hideAllSeries = function () {
    var boxes = document.getElementsByClassName('selectBox');

    for (var i = 0; i < boxes.length; i++) {
        var id = boxes[i].id;
        document.getElementById(id).checked = false;
        graph.setVisibility(getCheckboxIndex(id), false);
    }
};

// Handle for profile 'Apply' button
var applyProfileHandler = function (event) {
    var selectedIndex = document.getElementById('profiles').selectedIndex,
        profile = JSON.parse(document.getElementById('profiles').item(selectedIndex).value);

    hideAllSeries();
    for (id in profile) {
        document.getElementById(id).checked = true;
        graph.setVisibility(getCheckboxIndex(id), true);
    }
};
if (document.getElementById('applyProfile')) {
    document.getElementById('applyProfile').addEventListener('click',
                                                             applyProfileHandler,
                                                             false);
}

document.getElementById('newProfile')
    .addEventListener('click',
                      function () {
                          changeVisibility('newProfileDiv');
                      },
                      false);

document.getElementById('cancelProfile')
    .addEventListener('click',
                      function () {
                          document.getElementById('profileName').value = '';
                          changeVisibility('newProfileDiv', true);
                      },
                      false);

var showProfileControls = function () {
    changeVisibility('noProfiles', true);
    changeVisibility('profiles');
    changeVisibility('applyProfile');
    changeVisibility('deleteProfile');
};

var hideProfileControls = function () {
    changeVisibility('noProfiles');
    changeVisibility('profiles', true);
    changeVisibility('applyProfile', true);
    changeVisibility('deleteProfile', true);
};

// Handle for profile 'Save' button
var saveProfileHandler = function () {
    var profileName = document.getElementById('profileName').value;
    if (profileName === '') {
        alert('The profile name cannot be empty');
        return false;
    }

    var options = document.querySelectorAll('#profiles > option');
    for (var j = 0; j < options.length; j++) {
        if (profileName === options[j].text) {
            alert('A profile with the name ' + profileName + ' already exists');
            return false;
        }
    }

    var boxes = document.getElementsByClassName('selectBox'),
        checked = {};
    for (var i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) {
            checked[boxes[i].id] = boxes[i].checked;
        }
    }
    if (Object.keys(checked).length === 0) {
        alert('The profile cannot be empty');
        return false;
    }

    $.post('profile', {
        name: profileName,
        profile: JSON.stringify(checked)
    })
        .done(function (data, textStatus, jqXHR) {
            if (textStatus === 'success' && data === 'true') {
                alert('Profile successfully saved');

                var profileDropdown = document.getElementById('profiles'),
                    el = document.createElement('option');
                el.text = profileName;
                el.value = JSON.stringify(checked);
                profileDropdown.appendChild(el);
                if (profileDropdown.childElementCount === 1) {
                    showProfileControls();
                }
                changeVisibility('newProfileDiv', true);
                document.getElementById('profileName').value = '';
            } else {
                alert('An error occurred during profile saving');
            }
        })
        .fail(function (jqXHR, textStatus, errorThrown) {
            alert('An error occurred during profile saving');
            console.log('Profile save error: ' + errorThrown);
        });
};
document.getElementById('saveProfile').addEventListener('click',
                                                        saveProfileHandler,
                                                        false);

// Handler for the profile 'Delete' button
var deleteProfileHandler = function (event) {
    if (confirm('Are you sure you want to delete the profile?')) {
        var selectedIndex = document.getElementById('profiles').selectedIndex,
            name = document.getElementById('profiles').item(selectedIndex).text;

        $.ajax({
            url: 'profile',
            method: 'DELETE',
            data: {name: name}
        })
            .done(function (data, textStatus, jqXHR) {
                if (textStatus === 'success' && data === 'true') {
                    var profileDropdown = document.getElementById('profiles');
                    profileDropdown.removeChild(document.getElementById('profiles').item(selectedIndex));
                    if (!profileDropdown.childElementCount) {
                        hideProfileControls();
                    }
                    alert('Profile successfully deleted');
                } else {
                    alert('An error occurred during profile deletion');
                }
            })
            .fail(function (jqXHR, textStatus, errorThrown) {
                alert('An error occurred during profile deletion');
                console.log('Profile delete error: ' + errorThrown);
            });
    }
};

if (document.getElementById('deleteProfile')) {
    document.getElementById('deleteProfile').addEventListener('click',
                                                              deleteProfileHandler,
                                                              false);
}
