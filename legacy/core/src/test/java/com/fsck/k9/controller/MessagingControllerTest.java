package com.fsck.k9.controller;


import kotlin.Unit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import android.content.Context;
import net.thunderbird.core.android.account.LegacyAccountDto;
import net.thunderbird.core.featureflag.FeatureFlagProvider;
import net.thunderbird.core.featureflag.FeatureFlagResult.Disabled;
import app.k9mail.legacy.message.controller.SimpleMessagingListener;
import com.fsck.k9.K9;
import com.fsck.k9.K9RobolectricTest;
import com.fsck.k9.Preferences;
import com.fsck.k9.backend.BackendManager;
import org.mockito.ArgumentCaptor;
import com.fsck.k9.controller.MessagingControllerCommands.PendingSetServerCategories;
import com.fsck.k9.controller.MessagingControllerCommands.PendingCommand;
import com.fsck.k9.backend.api.Backend;
import com.fsck.k9.mail.AuthType;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateChainException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.ConnectionSecurity;
import net.thunderbird.core.common.mail.Flag;
import net.thunderbird.core.common.exception.MessagingException;
import com.fsck.k9.mail.ServerSettings;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.mailstore.recipients.RecipientIndex;
import com.fsck.k9.mailstore.LocalStoreProvider;
import app.k9mail.legacy.mailstore.ListenableMessageStore;
import app.k9mail.legacy.mailstore.MessageStoreManager;
import app.k9mail.legacy.message.controller.MessageReference;
import com.fsck.k9.mailstore.OutboxState;
import com.fsck.k9.mailstore.OutboxStateRepository;
import com.fsck.k9.mailstore.SaveMessageDataCreator;
import com.fsck.k9.mailstore.SendState;
import com.fsck.k9.mailstore.SpecialLocalFoldersCreator;
import com.fsck.k9.notification.NotificationController;
import com.fsck.k9.notification.NotificationStrategy;
import net.thunderbird.core.common.mail.Protocols;
import net.thunderbird.core.logging.Logger;
import net.thunderbird.components.core.outcome.Outcome;
import net.thunderbird.feature.account.AccountId;
import net.thunderbird.feature.mail.message.list.LocalDeleteOperationDecider;
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager;
import net.thunderbird.feature.mail.folder.api.OutboxFolderManagerKt;
import net.thunderbird.feature.mail.message.list.LocalMessageUidPrefixProvider;
import net.thunderbird.feature.notification.api.NotificationManager;
import net.thunderbird.feature.notification.testing.fake.FakeInAppOnlyNotification;
import net.thunderbird.feature.notification.testing.fake.FakeNotificationManager;
import net.thunderbird.legacy.core.StubLocalDeleteOperationDecider;
import net.thunderbird.legacy.core.mailstore.folder.FakeLocalMessageUidPrefixProvider;
import net.thunderbird.legacy.core.mailstore.folder.FakeOutboxFolderManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


@SuppressWarnings("unchecked")
public class MessagingControllerTest extends K9RobolectricTest {
    private static final long FOLDER_ID = 23;
    private static final String FOLDER_NAME = "Folder";
    private static final String SENDER_ADDRESS = "sender@example.com";
    private static final long SENT_FOLDER_ID = 10;
    private static final long ARCHIVE_FOLDER_ID = 24;
    private static final String ARCHIVE_FOLDER_NAME = "Archive";
    private static final int MAXIMUM_SMALL_MESSAGE_SIZE = 1000;

    private MessagingController controller;
    private UndoSendHold undoSendHold;
    private OutboxFolderManager outboxFolderManager;
    private LegacyAccountDto account;
    @Mock
    private BackendManager backendManager;
    @Mock
    private Backend backend;
    @Mock
    private LocalStoreProvider localStoreProvider;
    @Mock
    private MessageStoreManager messageStoreManager;
    @Mock
    private SaveMessageDataCreator saveMessageDataCreator;
    @Mock
    private SpecialLocalFoldersCreator specialLocalFoldersCreator;
    @Mock
    private SimpleMessagingListener listener;
    @Mock
    private LocalFolder localFolder;
    @Mock
    private LocalFolder sentFolder;
    @Mock
    private LocalStore localStore;
    @Mock
    private NotificationController notificationController;
    @Mock
    private NotificationStrategy notificationStrategy;
    @Mock
    private RecipientIndex recipientIndex;

    private Context appContext;
    private Set<Flag> reqFlags;
    private Set<Flag> forbiddenFlags;

    private List<String> remoteMessages;
    @Mock
    private LocalMessage localNewMessage1;
    @Mock
    private LocalMessage localNewMessage2;
    @Mock
    private LocalMessage localMessageToSend1;
    private volatile boolean hasFetchedMessage = false;

    private Preferences preferences;
    private AccountId accountId;
    private FeatureFlagProvider featureFlagProvider;

    @Mock
    private Logger syncLogger;

    @Before
    public void setUp() throws MessagingException {
        ShadowLog.stream = System.out;
        MockitoAnnotations.initMocks(this);
        appContext = RuntimeEnvironment.getApplication();

        preferences = Preferences.getPreferences();
        final LocalDeleteOperationDecider noOpLocalDeleteOperationDecider = new StubLocalDeleteOperationDecider();
        final LocalMessageUidPrefixProvider fakeLocalMessageUidPrefixProvider = new FakeLocalMessageUidPrefixProvider();
        featureFlagProvider = key -> Disabled.INSTANCE;

        final NotificationManager notificationManager = new FakeNotificationManager(
            notification -> Outcome.Companion.success(new FakeInAppOnlyNotification()),
            notification -> Outcome.Companion.success(new FakeInAppOnlyNotification()),
            id -> Outcome.Companion.success(new FakeInAppOnlyNotification())
        );

        outboxFolderManager = new FakeOutboxFolderManager(FOLDER_ID);

        // Holds for ten seconds and never lets go on its own, so a test can put a message on hold and keep it there.
        undoSendHold = new UndoSendHold(() -> 10, () -> 0L, (delayMillis, action) -> Unit.INSTANCE);

        controller = new MessagingController(
            appContext,
            notificationController,
            notificationStrategy,
            localStoreProvider,
            backendManager,
            preferences,
            messageStoreManager,
            saveMessageDataCreator,
            specialLocalFoldersCreator,
            noOpLocalDeleteOperationDecider,
            fakeLocalMessageUidPrefixProvider,
            recipientIndex,
            Collections.<ControllerExtension>emptyList(),
            featureFlagProvider,
            syncLogger,
            notificationManager,
            outboxFolderManager,
            undoSendHold
        );

        configureAccount();
        configureBackendManager();
        configureLocalStore();
    }

    @After
    public void tearDown() throws Exception {
        removeAccountsFromPreferences();
        controller.stop();
        autoClose();
    }

    @Test
    public void setServerCategoriesSynchronous_shouldStoreTheCategories() throws MessagingException {
        ListenableMessageStore messageStore = mock(ListenableMessageStore.class);
        when(messageStoreManager.getMessageStore(account)).thenReturn(messageStore);
        when(backend.getSupportsServerCategories()).thenReturn(true);
        List<String> categories = Arrays.asList("Red category", "Project X");

        controller.setServerCategoriesSynchronous(account, FOLDER_ID, 42L, "uid1", categories);

        verify(messageStore).setServerCategories(Collections.singletonList(42L), categories);
    }

    @Test
    public void setServerCategoriesSynchronous_shouldQueueTheChangeForTheServer() throws MessagingException {
        ListenableMessageStore messageStore = mock(ListenableMessageStore.class);
        when(messageStoreManager.getMessageStore(account)).thenReturn(messageStore);
        when(backend.getSupportsServerCategories()).thenReturn(true);
        List<String> categories = Collections.singletonList("Red category");

        controller.setServerCategoriesSynchronous(account, FOLDER_ID, 42L, "uid1", categories);

        ArgumentCaptor<PendingCommand> commandCaptor = ArgumentCaptor.forClass(PendingCommand.class);
        verify(localStore).addPendingCommand(commandCaptor.capture());
        PendingSetServerCategories command = (PendingSetServerCategories) commandCaptor.getValue();
        assertEquals(FOLDER_ID, command.folderId);
        assertEquals(categories, command.categories);
        assertEquals(Collections.singletonList("uid1"), command.uids);
    }

    @Test
    public void setServerCategoriesSynchronous_shouldNotQueueAnythingForABackendWithoutServerCategories()
        throws MessagingException {
        ListenableMessageStore messageStore = mock(ListenableMessageStore.class);
        when(messageStoreManager.getMessageStore(account)).thenReturn(messageStore);
        when(backend.getSupportsServerCategories()).thenReturn(false);

        controller.setServerCategoriesSynchronous(
            account, FOLDER_ID, 42L, "uid1", Collections.singletonList("Red category"));

        verify(localStore, never()).addPendingCommand(any(PendingCommand.class));
    }

    @Test
    public void processPendingSetServerCategories_shouldSendTheCategoriesToTheBackend() throws MessagingException {
        ListenableMessageStore messageStore = mock(ListenableMessageStore.class);
        when(messageStoreManager.getMessageStore(account)).thenReturn(messageStore);
        when(messageStore.getFolderServerId(FOLDER_ID)).thenReturn(FOLDER_NAME);
        List<String> categories = Arrays.asList("Red category", "Project X");
        List<String> uids = Collections.singletonList("uid1");

        controller.processPendingSetServerCategories(
            PendingSetServerCategories.create(FOLDER_ID, categories, uids), account);

        verify(backend).setServerCategories(FOLDER_NAME, uids, categories);
    }

    @Test
    public void clearFolderSynchronous_shouldOpenFolderForWriting() throws MessagingException {
        controller.clearFolderSynchronous(account, FOLDER_ID);

        verify(localFolder).open();
    }

    @Test
    public void clearFolderSynchronous_shouldClearAllMessagesInTheFolder() throws MessagingException {
        controller.clearFolderSynchronous(account, FOLDER_ID);

        verify(localFolder).clearAllMessages();
    }

    @Test
    public void refreshRemoteSynchronous_shouldCallBackend() throws MessagingException {
        controller.refreshFolderListSynchronous(account);

        verify(backend).refreshFolderList();
    }

    private void setupRemoteSearch() throws Exception {
        remoteMessages = new ArrayList<>();
        Collections.addAll(remoteMessages, "oldMessageUid", "newMessageUid1", "newMessageUid2");
        List<String> newRemoteMessages = new ArrayList<>();
        Collections.addAll(newRemoteMessages, "newMessageUid1", "newMessageUid2");

        when(localNewMessage1.getUid()).thenReturn("newMessageUid1");
        when(localNewMessage2.getUid()).thenReturn("newMessageUid2");
        when(backend.search(eq(FOLDER_NAME), anyString(), nullable(Set.class), nullable(Set.class), eq(false)))
            .thenReturn(remoteMessages);
        when(localFolder.extractNewMessages(ArgumentMatchers.<String>anyList())).thenReturn(newRemoteMessages);
        when(localFolder.getMessage("newMessageUid1")).thenReturn(localNewMessage1);
        when(localFolder.getMessage("newMessageUid2")).thenAnswer(
            new Answer<LocalMessage>() {
                @Override
                public LocalMessage answer(InvocationOnMock invocation) throws Throwable {
                    if(hasFetchedMessage) {
                        return localNewMessage2;
                    }
                    else
                        return null;
                }
            }
        );
        doAnswer((Answer<Void>) invocation -> {
            hasFetchedMessage = true;
            return null;
        }).when(backend).downloadMessageStructure(eq(FOLDER_NAME), eq("newMessageUid2"));
        reqFlags = Collections.singleton(Flag.ANSWERED);
        forbiddenFlags = Collections.singleton(Flag.DELETED);

        account.setRemoteSearchNumResults(50);
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldNotifyStartedListingRemoteMessages() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(listener).remoteSearchStarted(FOLDER_ID);
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldQueryRemoteFolder() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(backend).search(FOLDER_NAME, "query", reqFlags, forbiddenFlags, false);
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldAskLocalFolderToDetermineNewMessages() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(localFolder).extractNewMessages(remoteMessages);
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldTryAndGetNewMessages() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(localFolder).getMessage("newMessageUid1");
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldNotTryAndGetOldMessages() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(localFolder, never()).getMessage("oldMessageUid");
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldFetchNewMessages() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(backend).downloadMessageStructure(eq(FOLDER_NAME), eq("newMessageUid2"));
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldNotFetchExistingMessages() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(backend, never()).downloadMessageStructure(eq(FOLDER_NAME), eq("newMessageUid1"));
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldNotifyOnFailure() throws Exception {
        setupRemoteSearch();
        when(backend.search(anyString(), anyString(), nullable(Set.class), nullable(Set.class), eq(false)))
            .thenThrow(new MessagingException("Test"));

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(listener).remoteSearchFailed(null, "Test");
    }

    @Test
    public void searchRemoteMessagesSynchronous_shouldNotifyOnFinish() throws Exception {
        setupRemoteSearch();
        when(backend.search(anyString(), nullable(String.class), nullable(Set.class), nullable(Set.class), eq(false)))
            .thenThrow(new MessagingException("Test"));

        controller.searchRemoteMessagesSynchronous(accountId, FOLDER_ID, "query", reqFlags, forbiddenFlags, listener);

        verify(listener).remoteSearchFinished(FOLDER_ID, 0, 50, Collections.<String>emptyList());
    }

    @Test
    public void sendPendingMessagesSynchronous_withNonExistentOutbox_shouldNotStartSync() throws MessagingException {
        when(localFolder.exists()).thenReturn(false);
        controller.addListener(listener);

        controller.sendPendingMessagesSynchronous(account);

        verifyNoMoreInteractions(listener);
    }

    @Test
    public void sendPendingMessagesSynchronous_whenServerFilesItsOwnSentCopy_shouldNotUploadAnother()
        throws MessagingException {
        setupAccountWithMessageToSend();
        account.setUploadSentMessages(true);
        when(backend.getSavesSentMessages()).thenReturn(true);

        controller.sendPendingMessagesSynchronous(account);

        // The server's copy arrives with the next sync of the Sent folder; keeping this one would show it twice.
        verify(localMessageToSend1).destroy();
        verify(backend, never()).uploadMessage(anyString(), any(Message.class));
    }

    @Test
    public void sendPendingMessagesSynchronous_shouldNotSendAMessageStillHeldForUndo() throws MessagingException {
        setupAccountWithMessageToSend();
        undoSendHold.hold(account.getId().toString(), 42L, () -> { });

        controller.sendPendingMessagesSynchronous(account);

        verify(backend, never()).sendMessage(any(Message.class));
    }

    @Test
    public void undoSend_shouldReopenTheDraftUnderTheIdItHasOnceUploaded() throws MessagingException {
        // Saving the draft starts uploading it, and the upload swaps its temporary server ID for the real one.
        // Reopened under the temporary one, it would not be found.
        long draftsFolderId = 30L;
        long draftId = 99L;
        String[] draftServerId = { "K9LOCAL:draft" };
        account.setDraftsFolderId(draftsFolderId);
        OutboxFolderManagerKt.getOutboxFolderIdSync(outboxFolderManager, account.getId().toString(), true);
        ListenableMessageStore messageStore = mock(ListenableMessageStore.class);
        when(messageStoreManager.getMessageStore(account)).thenReturn(messageStore);
        when(messageStore.getMessageServerId(42L)).thenReturn("localMessageToSend1");
        when(messageStore.getMessageServerId(draftId)).thenAnswer(invocation -> draftServerId[0]);
        when(messageStore.saveLocalMessage(eq(draftsFolderId), any(), nullable(Long.class))).thenReturn(draftId);
        when(localFolder.getMessage("localMessageToSend1")).thenReturn(localMessageToSend1);
        when(backend.getSupportsUpload()).thenReturn(true);
        when(localStore.getPendingCommands()).thenAnswer(invocation -> {
            draftServerId[0] = "uploaded-draft";
            return emptyList();
        });
        undoSendHold.hold(account.getId().toString(), 42L, () -> { });

        MessageReference draft = controller.undoSend(account, 42L);

        assertEquals(new MessageReference(account.getId(), draftsFolderId, "uploaded-draft"), draft);
    }

    @Test
    public void sendPendingMessagesSynchronous_shouldSetProgress() throws MessagingException {
        setupAccountWithMessageToSend();

        controller.sendPendingMessagesSynchronous(account);

        verify(listener).synchronizeMailboxProgress(account, FOLDER_ID, 0, 1);
    }

    @Test
    public void sendPendingMessagesSynchronous_shouldSendMessageUsingTransport() throws MessagingException {
        setupAccountWithMessageToSend();

        controller.sendPendingMessagesSynchronous(account);

        verify(backend).sendMessage(localMessageToSend1);
    }

    @Test
    public void sendPendingMessagesSynchronous_shouldSetAndRemoveSendInProgressFlag() throws MessagingException {
        setupAccountWithMessageToSend();

        controller.sendPendingMessagesSynchronous(account);

        InOrder ordering = inOrder(localMessageToSend1, backend);
        ordering.verify(localMessageToSend1).setFlag(Flag.X_SEND_IN_PROGRESS, true);
        ordering.verify(backend).sendMessage(localMessageToSend1);
        ordering.verify(localMessageToSend1).setFlag(Flag.X_SEND_IN_PROGRESS, false);
    }

    @Test
    public void sendPendingMessagesSynchronous_shouldMarkSentMessageAsSeen() throws MessagingException {
        setupAccountWithMessageToSend();

        controller.sendPendingMessagesSynchronous(account);

        verify(localMessageToSend1).setFlag(Flag.SEEN, true);
    }

    @Test
    public void sendPendingMessagesSynchronous_whenMessageSentSuccesfully_shouldUpdateProgress() throws MessagingException {
        setupAccountWithMessageToSend();

        controller.sendPendingMessagesSynchronous(account);

        verify(listener).synchronizeMailboxProgress(account, FOLDER_ID, 1, 1);
    }

    @Test
    public void sendPendingMessagesSynchronous_shouldUpdateProgress() throws MessagingException {
        setupAccountWithMessageToSend();

        controller.sendPendingMessagesSynchronous(account);

        verify(listener).synchronizeMailboxProgress(account, FOLDER_ID, 1, 1);
    }

    @Test
    public void sendPendingMessagesSynchronous_withAuthenticationFailure_shouldNotify() throws MessagingException {
        setupAccountWithMessageToSend();
        doThrow(new AuthenticationFailedException("Test")).when(backend).sendMessage(localMessageToSend1);

        controller.sendPendingMessagesSynchronous(account);

        verify(notificationController).showAuthenticationErrorNotification(account, false);
    }

    @Test
    public void sendPendingMessagesSynchronous_withCertificateFailure_shouldNotify() throws MessagingException {
        setupAccountWithMessageToSend();
        doThrow(new CertificateValidationException(emptyList(), new CertificateChainException("", null, null)))
            .when(backend).sendMessage(localMessageToSend1);

        controller.sendPendingMessagesSynchronous(account);

        verify(notificationController).showCertificateErrorNotification(account, false);
    }

    private void setupAccountWithMessageToSend() throws MessagingException {
        account.setSentFolderId(SENT_FOLDER_ID);
        when(localStore.getFolder(SENT_FOLDER_ID)).thenReturn(sentFolder);
        when(sentFolder.getDatabaseId()).thenReturn(SENT_FOLDER_ID);
        when(localFolder.exists()).thenReturn(true);
        when(localFolder.getMessages()).thenReturn(Collections.singletonList(localMessageToSend1));
        when(localMessageToSend1.getUid()).thenReturn("localMessageToSend1");
        when(localMessageToSend1.getDatabaseId()).thenReturn(42L);
        when(localMessageToSend1.getHeader(K9.IDENTITY_HEADER)).thenReturn(new String[]{});

        OutboxState outboxState = new OutboxState(SendState.READY, 0, null, 0);
        OutboxStateRepository outboxStateRepository = mock(OutboxStateRepository.class);
        when(outboxStateRepository.getOutboxState(42L)).thenReturn(outboxState);

        when(localStore.getOutboxStateRepository()).thenReturn(outboxStateRepository);
        controller.addListener(listener);
    }

    private void configureBackendManager() throws MessagingException {
        when(backendManager.getBackend(account.getId())).thenReturn(backend);
        // What a server that can only be searched folder by folder answers. Left unstubbed, Mockito would answer
        // with an empty map instead, which reads as "searched everything, found nothing".
        when(backend.searchAllFolders(nullable(String.class), nullable(Set.class), nullable(Set.class), anyBoolean()))
            .thenReturn(null);
        when(backend.searchAllFoldersFromSender(nullable(String.class))).thenReturn(null);
    }

    private void configureAccount() {
        account = preferences.newAccount();
        accountId = account.getId();

        account.setIncomingServerSettings(new ServerSettings(Protocols.IMAP, "host", 993,
            ConnectionSecurity.SSL_TLS_REQUIRED, AuthType.PLAIN, "username", "password", null));
        account.setOutgoingServerSettings(new ServerSettings(Protocols.SMTP, "host", 465,
            ConnectionSecurity.SSL_TLS_REQUIRED, AuthType.PLAIN, "username", "password", null));
        account.setMaximumAutoDownloadMessageSize(MAXIMUM_SMALL_MESSAGE_SIZE);
        account.setEmail("user@host.com");
    }

    @Test
    public void searchRemoteMessagesEverywhere_withServerSearchingAllFoldersAtOnce_shouldNotSearchFolderByFolder()
        throws Exception {
        // One request for the whole mailbox rather than one per folder, where the server can.
        setupRemoteSearch();
        when(backend.searchAllFolders(eq("query"), nullable(Set.class), nullable(Set.class), anyBoolean()))
            .thenReturn(Collections.singletonMap(FOLDER_NAME, remoteMessages));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(backend, never()).search(anyString(), anyString(), nullable(Set.class), nullable(Set.class),
            anyBoolean());
        verify(localFolder).extractNewMessages(remoteMessages);
        verify(listener).remoteSearchServerQueryComplete(eq(FOLDER_ID), anyInt(), anyInt());
    }

    @Test
    public void searchRemoteMessagesEverywhere_whenSearchingAllFoldersAtOnceFails_shouldSearchFolderByFolder()
        throws Exception {
        setupRemoteSearch();
        when(backend.searchAllFolders(anyString(), nullable(Set.class), nullable(Set.class), anyBoolean()))
            .thenThrow(new MessagingException("throttled"));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(backend).search(FOLDER_NAME, "query", reqFlags, forbiddenFlags, false);
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldSearchEveryFolderOfTheAccount() throws Exception {
        // The point of the feature: mail worth finding is rarely still in the folder the user is standing in.
        setupRemoteSearch();
        LocalFolder archive = configureExtraFolder(ARCHIVE_FOLDER_ID, ARCHIVE_FOLDER_NAME);
        when(localStore.getPersonalNamespaces(false)).thenReturn(Arrays.asList(localFolder, archive));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(backend).search(FOLDER_NAME, "query", reqFlags, forbiddenFlags, false);
        verify(backend).search(ARCHIVE_FOLDER_NAME, "query", reqFlags, forbiddenFlags, false);
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldSkipFoldersThatOnlyExistOnTheDevice() throws Exception {
        // The Outbox has nothing for a server to search.
        setupRemoteSearch();
        LocalFolder outbox = configureExtraFolder(ARCHIVE_FOLDER_ID, ARCHIVE_FOLDER_NAME);
        when(outbox.isLocalOnly()).thenReturn(true);
        when(localStore.getPersonalNamespaces(false)).thenReturn(Arrays.asList(localFolder, outbox));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(backend, never()).search(eq(ARCHIVE_FOLDER_NAME), anyString(), nullable(Set.class),
            nullable(Set.class), anyBoolean());
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldKeepGoingAfterAFolderFails() throws Exception {
        // One folder the server refuses must not cost the user every other folder.
        setupRemoteSearch();
        LocalFolder archive = configureExtraFolder(ARCHIVE_FOLDER_ID, ARCHIVE_FOLDER_NAME);
        when(localStore.getPersonalNamespaces(false)).thenReturn(Arrays.asList(archive, localFolder));
        when(backend.search(eq(ARCHIVE_FOLDER_NAME), anyString(), nullable(Set.class), nullable(Set.class),
            anyBoolean())).thenThrow(new MessagingException("nope"));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(backend).search(FOLDER_NAME, "query", reqFlags, forbiddenFlags, false);
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldSkipAccountsWhoseServerCannotSearch() throws Exception {
        // POP3 can neither push nor search, and asking it would only produce an error the user cannot act on.
        setupRemoteSearch();
        when(backend.isPushCapable()).thenReturn(false);

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(backend, never()).search(anyString(), anyString(), nullable(Set.class), nullable(Set.class),
            anyBoolean());
    }

    @Test
    public void searchRemoteSenderEverywhere_shouldAskTheServerForMailFromTheSender() throws Exception {
        // Searching for the address as text would also find, and spend the result limit on, mail sent to it.
        setupRemoteSearch();
        when(backend.searchAllFoldersFromSender(SENDER_ADDRESS))
            .thenReturn(Collections.singletonMap(FOLDER_NAME, remoteMessages));

        controller.searchRemoteSenderEverywhereSynchronous(Collections.<String>emptyList(), SENDER_ADDRESS, listener);

        verify(backend, never()).searchAllFolders(anyString(), nullable(Set.class), nullable(Set.class),
            anyBoolean());
        verify(localFolder).extractNewMessages(remoteMessages);
        verify(listener).remoteSearchServerQueryComplete(eq(FOLDER_ID), anyInt(), anyInt());
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldSaveMatchesInAFolderNamedByItsServerId() throws Exception {
        // A folder made from a server id has no database id until it is opened, so it must not be asked whether
        // it exists first: that answered "no" for every folder, and every match was dropped.
        setupRemoteSearch();
        when(localFolder.exists()).thenReturn(false);
        when(backend.searchAllFolders(eq("query"), nullable(Set.class), nullable(Set.class), anyBoolean()))
            .thenReturn(Collections.singletonMap(FOLDER_NAME, remoteMessages));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(localFolder).extractNewMessages(remoteMessages);
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldSkipMatchesInAFolderTheDeviceDoesNotHave() throws Exception {
        // Its matches have nowhere to go until the next folder list refresh.
        setupRemoteSearch();
        doThrow(new MessagingException("Folder not found")).when(localFolder).open();
        when(backend.searchAllFolders(eq("query"), nullable(Set.class), nullable(Set.class), anyBoolean()))
            .thenReturn(Collections.singletonMap(FOLDER_NAME, remoteMessages));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(localFolder, never()).extractNewMessages(ArgumentMatchers.<String>anyList());
        verify(listener, never()).remoteSearchFailed(nullable(String.class), nullable(String.class));
    }

    @Test
    public void searchRemoteSenderEverywhere_whenTheServerCannotTellSendersApart_shouldSearchForTheAddress()
        throws Exception {
        setupRemoteSearch();

        controller.searchRemoteSenderEverywhereSynchronous(Collections.<String>emptyList(), SENDER_ADDRESS, listener);

        verify(backend).search(FOLDER_NAME, SENDER_ADDRESS, null, null, false);
    }

    @Test
    public void searchRemoteSenderEverywhere_shouldDownloadPastTheResultLimit() throws Exception {
        // The sender's list is meant to show all of their mail, not the first few matches of a quick search.
        setupRemoteSearch();
        account.setRemoteSearchNumResults(1);
        when(backend.searchAllFoldersFromSender(SENDER_ADDRESS))
            .thenReturn(Collections.singletonMap(FOLDER_NAME, remoteMessages));

        controller.searchRemoteSenderEverywhereSynchronous(Collections.<String>emptyList(), SENDER_ADDRESS, listener);

        verify(backend).downloadMessageStructure(eq(FOLDER_NAME), eq("newMessageUid2"));
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldNotSearchAnAccountItWasNotAsked() throws Exception {
        setupRemoteSearch();

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.singletonList("some-other-account"),
            "query", reqFlags, forbiddenFlags, listener);

        verify(backend, never()).search(anyString(), anyString(), nullable(Set.class), nullable(Set.class),
            anyBoolean());
    }

    @Test
    public void searchRemoteMessagesEverywhere_shouldReportStartAndFinishOnce() throws Exception {
        // The progress indicator is driven by these, so one per folder would leave it stuck on.
        setupRemoteSearch();
        LocalFolder archive = configureExtraFolder(ARCHIVE_FOLDER_ID, ARCHIVE_FOLDER_NAME);
        when(localStore.getPersonalNamespaces(false)).thenReturn(Arrays.asList(localFolder, archive));

        controller.searchRemoteMessagesEverywhereSynchronous(Collections.<String>emptyList(), "query", reqFlags,
            forbiddenFlags, listener);

        verify(listener, times(1)).remoteSearchStarted(anyLong());
        verify(listener, times(1)).remoteSearchFinished(anyLong(), anyInt(), anyInt(),
            ArgumentMatchers.<String>anyList());
    }

    private LocalFolder configureExtraFolder(long folderId, String serverId) throws MessagingException {
        LocalFolder folder = mock(LocalFolder.class);
        when(folder.exists()).thenReturn(true);
        when(folder.getDatabaseId()).thenReturn(folderId);
        when(folder.getServerId()).thenReturn(serverId);
        when(folder.extractNewMessages(ArgumentMatchers.<String>anyList())).thenReturn(
            Collections.<String>emptyList());
        when(localStore.getFolder(folderId)).thenReturn(folder);

        return folder;
    }

    private void configureLocalStore() throws MessagingException {
        when(localStore.getFolder(FOLDER_NAME)).thenReturn(localFolder);
        when(localStore.getFolder(FOLDER_ID)).thenReturn(localFolder);
        when(localFolder.exists()).thenReturn(true);
        when(localFolder.getDatabaseId()).thenReturn(FOLDER_ID);
        when(localFolder.getServerId()).thenReturn(FOLDER_NAME);
        when(localStore.getPersonalNamespaces(false)).thenReturn(Collections.singletonList(localFolder));
        when(localStoreProvider.getInstance(account)).thenReturn(localStore);
        when(localFolder.isLocalOnly()).thenReturn(false);
        when(backend.isPushCapable()).thenReturn(true);
    }

    private void removeAccountsFromPreferences() {
        preferences.clearAccounts();
    }
}
