/**
 *
 * Copyright 2015-2020 Florian Schmaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.smackx.muc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeoutException;

import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.util.StringUtils;

import org.jivesoftware.smackx.muc.MultiUserChat.MucCreateConfigFormHandle;
import org.jivesoftware.smackx.muc.MultiUserChatException.MucNotJoinedException;
import org.jivesoftware.smackx.muc.MultiUserChatException.NotAMucServiceException;
import org.jivesoftware.smackx.muc.packet.MUCUser;

import org.igniterealtime.smack.inttest.AbstractSmackIntegrationTest;
import org.igniterealtime.smack.inttest.SmackIntegrationTestEnvironment;
import org.igniterealtime.smack.inttest.TestNotPossibleException;
import org.igniterealtime.smack.inttest.annotations.SmackIntegrationTest;
import org.igniterealtime.smack.inttest.util.ResultSyncPoint;
import org.igniterealtime.smack.inttest.util.SimpleResultSyncPoint;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;

public class MultiUserChatIntegrationTest extends AbstractSmackIntegrationTest {

    private final String randomString = StringUtils.insecureRandomString(6);

    private final MultiUserChatManager mucManagerOne;
    private final MultiUserChatManager mucManagerTwo;
    private final MultiUserChatManager mucManagerThree;
    private final DomainBareJid mucService;

    public MultiUserChatIntegrationTest(SmackIntegrationTestEnvironment environment)
                    throws NoResponseException, XMPPErrorException, NotConnectedException,
                    InterruptedException, TestNotPossibleException {
        super(environment);
        mucManagerOne = MultiUserChatManager.getInstanceFor(conOne);
        mucManagerTwo = MultiUserChatManager.getInstanceFor(conTwo);
        mucManagerThree = MultiUserChatManager.getInstanceFor(conThree);

        List<DomainBareJid> services = mucManagerOne.getMucServiceDomains();
        if (services.isEmpty()) {
            throw new TestNotPossibleException("No MUC (XEP-45) service found");
        }
        else {
            mucService = services.get(0);
        }
    }

    @SmackIntegrationTest
    public void mucJoinLeaveTest() throws XmppStringprepException, NotAMucServiceException, NoResponseException,
            XMPPErrorException, NotConnectedException, InterruptedException, MucNotJoinedException {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-join-leave-" + randomString),
                mucService.getDomain());

        MultiUserChat muc = mucManagerOne.getMultiUserChat(mucAddress);

        muc.join(Resourcepart.from("nick-one"));

        Presence reflectedLeavePresence = muc.leave();

        MUCUser mucUser = MUCUser.from(reflectedLeavePresence);
        assertNotNull(mucUser);

        assertTrue(mucUser.getStatus().contains(MUCUser.Status.PRESENCE_TO_SELF_110));
    }

    @SmackIntegrationTest
    public void mucTest() throws Exception {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-" + randomString), mucService.getDomain());

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);

        final String mucMessage = "Smack Integration Test MUC Test Message " + randomString;
        final ResultSyncPoint<String, Exception> resultSyncPoint = new ResultSyncPoint<>();

        mucAsSeenByTwo.addMessageListener(new MessageListener() {
            @Override
            public void processMessage(Message message) {
                String body = message.getBody();
                if (mucMessage.equals(body)) {
                    resultSyncPoint.signal(body);
                }
            }
        });

        MucCreateConfigFormHandle handle = mucAsSeenByOne.createOrJoin(Resourcepart.from("one-" + randomString));
        if (handle != null) {
            handle.makeInstant();
        }
        mucAsSeenByTwo.join(Resourcepart.from("two-" + randomString));

        mucAsSeenByOne.sendMessage(mucMessage);
        try{
            resultSyncPoint.waitForResult(timeout);
        } finally {
            mucAsSeenByTwo.leave();
            mucAsSeenByOne.leave();
        }
    }

    /**
     * Asserts that a user who undergoes an role change receives that change as a presence update
     *
     * <p>From XEP-0045 § 5.1.3:</p>
     * <blockquote>
     * ...a MUC service implementation MUST change the occupant's role to reflect the change and communicate the change to all occupants...
     * </blockquote>
     *
     * <p>From XEP-0045 § 9.6:</p>
     * <blockquote>
     * The service MUST then send updated presence from this individual to all occupants, indicating the addition of moderator status...
     * </blockquote>
     *
     * @throws Exception
     */
    @SmackIntegrationTest
    public void mucRoleTestForReceivingModerator() throws Exception {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-" + randomString), mucService.getDomain());

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);

        final ResultSyncPoint<String, Exception> resultSyncPoint = new ResultSyncPoint<>();


        mucAsSeenByTwo.addParticipantStatusListener(new ParticipantStatusListener() {
            @Override
            public void moderatorGranted(EntityFullJid participant) {
                resultSyncPoint.signal("done");
            }
        });

        MucCreateConfigFormHandle handle = mucAsSeenByOne.createOrJoin(Resourcepart.from("one-" + randomString));
        if (handle != null) {
            handle.makeInstant();
        }

        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        mucAsSeenByTwo.join(nicknameTwo);

        //This implicitly tests "The service MUST add the user to the moderator list and then inform the admin of success" in §9.6, since it'll throw on either an error IQ or on no response.
        mucAsSeenByOne.grantModerator(nicknameTwo);
        try{
            resultSyncPoint.waitForResult(timeout);
        } finally {
            mucAsSeenByTwo.leave();
            mucAsSeenByOne.leave();
        }
    }

    /**
     * Asserts that a user who is present when another user undergoes an role change receives that change as a presence update
     *
     * <p>From XEP-0045 § 5.1.3:</p>
     * <blockquote>
     * ...a MUC service implementation MUST change the occupant's role to reflect the change and communicate the change to all occupants...
     * </blockquote>
     *
     * <p>From XEP-0045 § 9.6:</p>
     * <blockquote>
     * The service MUST then send updated presence from this individual to all occupants, indicating the addition of moderator status...
     * </blockquote>
     *
     * @throws Exception
     */
    @SmackIntegrationTest
    public void mucRoleTestForWitnessingModerator() throws Exception {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-" + randomString), mucService.getDomain());

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByThree = mucManagerThree.getMultiUserChat(mucAddress);

        final ResultSyncPoint<String, Exception> resultSyncPoint = new ResultSyncPoint<>();

        mucAsSeenByThree.addParticipantStatusListener(new ParticipantStatusListener() {
            @Override
            public void moderatorGranted(EntityFullJid participant) {
                resultSyncPoint.signal("done");
            }
        });

        MucCreateConfigFormHandle handle = mucAsSeenByOne.createOrJoin(Resourcepart.from("one-" + randomString));
        if (handle != null) {
            handle.makeInstant();
        }

        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        final Resourcepart nicknameThree = Resourcepart.from("three-" + randomString);
        mucAsSeenByTwo.join(nicknameTwo);
        mucAsSeenByThree.join(nicknameThree);

        mucAsSeenByOne.grantModerator(nicknameTwo);
        try{
            resultSyncPoint.waitForResult(timeout);
        } finally {
            mucAsSeenByTwo.leave();
            mucAsSeenByOne.leave();
            mucAsSeenByThree.leave();
        }

    }

    /**
     * Asserts that a user who undergoes an role change receives that change as a presence update
     *
     * <p>From XEP-0045 § 5.1.3:</p>
     * <blockquote>
     * ...a MUC service implementation MUST change the occupant's role to reflect the change and communicate the change to all occupants...
     * </blockquote>
     *
     * <p>From XEP-0045 § 9.7:</p>
     * <blockquote>
     * The service MUST then send updated presence from this individual to all occupants, indicating the removal of moderator status...
     * </blockquote>
     *
     * @throws Exception
     */
    @SmackIntegrationTest
    public void mucRoleTestForRemovingModerator() throws Exception {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-" + randomString), mucService.getDomain());

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);

        final ResultSyncPoint<String, Exception> resultSyncPoint = new ResultSyncPoint<>();

        mucAsSeenByTwo.addParticipantStatusListener(new ParticipantStatusListener() {
            @Override
            public void moderatorRevoked(EntityFullJid participant) {
                resultSyncPoint.signal("done");
            }
        });

        MucCreateConfigFormHandle handle = mucAsSeenByOne.createOrJoin(Resourcepart.from("one-" + randomString));
        if (handle != null) {
            handle.makeInstant();
        }

        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        mucAsSeenByTwo.join(nicknameTwo);

        mucAsSeenByOne.grantModerator(nicknameTwo);
        mucAsSeenByOne.revokeModerator(nicknameTwo);
        try{
            resultSyncPoint.waitForResult(timeout);
        } finally {
            mucAsSeenByTwo.leave();
            mucAsSeenByOne.leave();
        }
    }

    /**
     * Asserts that a user who is present when another user undergoes an role change receives that change as a presence update
     *
     * <p>From XEP-0045 § 5.1.3:</p>
     * <blockquote>
     * ...a MUC service implementation MUST change the occupant's role to reflect the change and communicate the change to all occupants...
     * </blockquote>
     *
     * <p>From XEP-0045 § 9.6:</p>
     * <blockquote>
     * The service MUST then send updated presence from this individual to all occupants, indicating the removal of moderator status...
     * </blockquote>
     *
     * @throws Exception
     */
    @SmackIntegrationTest
    public void mucRoleTestForWitnessingModeratorRemoval() throws Exception {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-" + randomString), mucService.getDomain());

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByThree = mucManagerThree.getMultiUserChat(mucAddress);

        final ResultSyncPoint<String, Exception> resultSyncPoint = new ResultSyncPoint<>();

        mucAsSeenByThree.addParticipantStatusListener(new ParticipantStatusListener() {
            @Override
            public void moderatorRevoked(EntityFullJid participant) {
                resultSyncPoint.signal("done");
            }
        });

        MucCreateConfigFormHandle handle = mucAsSeenByOne.createOrJoin(Resourcepart.from("one-" + randomString));
        if (handle != null) {
            handle.makeInstant();
        }

        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        final Resourcepart nicknameThree = Resourcepart.from("three-" + randomString);
        mucAsSeenByTwo.join(nicknameTwo);
        mucAsSeenByThree.join(nicknameThree);

        mucAsSeenByOne.grantModerator(nicknameTwo);
        mucAsSeenByOne.revokeModerator(nicknameTwo);
        try{
            resultSyncPoint.waitForResult(timeout);
        } finally {
            mucAsSeenByTwo.leave();
            mucAsSeenByOne.leave();
            mucAsSeenByThree.leave();
        }

    }

    /**
     * Asserts that a user in an unmoderated room who undergoes an afilliation change receives that change as a presence update
     *
     * <p>From XEP-0045 § 5.1.3:</p>
     * <blockquote>
     * ...a MUC service implementation MUST change the occupant's role to reflect the change and communicate the change to all occupants...
     * </blockquote>
     *
     * <p>From XEP-0045 § 8.4:</p>
     * <blockquote>
     * The service MUST then send updated presence from this individual to all occupants, indicating the removal of voice privileges...
     * </blockquote>
     *
     * @throws Exception
     */
    @SmackIntegrationTest
    public void mucRoleTestForRevokingVoice() throws Exception {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-" + randomString), mucService.getDomain());

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);

        final ResultSyncPoint<String, Exception> resultSyncPoint = new ResultSyncPoint<>();

        mucAsSeenByTwo.addParticipantStatusListener(new ParticipantStatusListener() {
            @Override
            public void voiceRevoked(EntityFullJid participant) {
                resultSyncPoint.signal("done");
            }
        });

        MucCreateConfigFormHandle handle = mucAsSeenByOne.createOrJoin(Resourcepart.from("one-" + randomString));
        if (handle != null) {
            handle.makeInstant();
        }

        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        mucAsSeenByTwo.join(nicknameTwo);
        mucAsSeenByOne.revokeVoice(nicknameTwo);

        try{
            resultSyncPoint.waitForResult(timeout);
        } finally {
            mucAsSeenByTwo.leave();
            mucAsSeenByOne.leave();
        }
    }

    /**
     * Asserts that a user who is present when another user undergoes an role change receives that change as a presence update
     *
     * <p>From XEP-0045 § 5.1.3:</p>
     * <blockquote>
     * ...a MUC service implementation MUST change the occupant's role to reflect the change and communicate the change to all occupants...
     * </blockquote>
     *
     * <p>From XEP-0045 § 8.4:</p>
     * <blockquote>
     * The service MUST then send updated presence from this individual to all occupants, indicating the removal of voice privileges...
     * </blockquote>
     *
     * @throws Exception
     */
    @SmackIntegrationTest
    public void mucRoleTestForWitnessingRevokingVoice() throws Exception {
        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-" + randomString), mucService.getDomain());

        MultiUserChat mucAsSeenByOne = mucManagerOne.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByTwo = mucManagerTwo.getMultiUserChat(mucAddress);
        MultiUserChat mucAsSeenByThree = mucManagerThree.getMultiUserChat(mucAddress);

        final ResultSyncPoint<String, Exception> resultSyncPoint = new ResultSyncPoint<>();

        mucAsSeenByThree.addParticipantStatusListener(new ParticipantStatusListener() {
            @Override
            public void voiceRevoked(EntityFullJid participant) {
                resultSyncPoint.signal("done");
            }
        });

        MucCreateConfigFormHandle handle = mucAsSeenByOne.createOrJoin(Resourcepart.from("one-" + randomString));
        if (handle != null) {
            handle.makeInstant();
        }

        final Resourcepart nicknameTwo = Resourcepart.from("two-" + randomString);
        final Resourcepart nicknameThree = Resourcepart.from("three-" + randomString);
        mucAsSeenByTwo.join(nicknameTwo);
        mucAsSeenByThree.join(nicknameThree);

        mucAsSeenByOne.revokeVoice(nicknameTwo);

        try{
            resultSyncPoint.waitForResult(timeout);
        } finally {
            mucAsSeenByTwo.leave();
            mucAsSeenByOne.leave();
            mucAsSeenByThree.leave();
        }
    }

    @SmackIntegrationTest
    public void mucDestroyTest() throws TimeoutException, Exception {

        EntityBareJid mucAddress = JidCreate.entityBareFrom(Localpart.from("smack-inttest-join-leave-" + randomString),
                                                            mucService.getDomain());

        MultiUserChat muc = mucManagerOne.getMultiUserChat(mucAddress);
        muc.join(Resourcepart.from("nick-one"));

        final SimpleResultSyncPoint mucDestroyed = new SimpleResultSyncPoint();

        @SuppressWarnings("deprecation")
        DefaultUserStatusListener userStatusListener = new DefaultUserStatusListener() {
            @Override
            public void roomDestroyed(MultiUserChat alternateMUC, String reason) {
                mucDestroyed.signal();
            }
        };

        muc.addUserStatusListener(userStatusListener);

        assertEquals(1, mucManagerOne.getJoinedRooms().size());
        assertEquals(1, muc.getOccupantsCount());
        assertNotNull(muc.getNickname());

        try {
            muc.destroy("Dummy reason", null);
            mucDestroyed.waitForResult(timeout);
        } finally {
            muc.removeUserStatusListener(userStatusListener);
        }

        assertEquals(0, mucManagerOne.getJoinedRooms().size());
        assertEquals(0, muc.getOccupantsCount());
        assertNull(muc.getNickname());
    }
}
