package com.fsck.k9.view;


import java.util.Collections;
import java.util.List;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.ImageView;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.TooltipCompat;
import app.k9mail.core.ui.legacy.designsystem.atom.icon.Icons;
import app.k9mail.legacy.di.DI;
import com.fsck.k9.FontSizes;
import com.fsck.k9.K9;
import com.fsck.k9.activity.misc.ContactPicture;
import app.k9mail.core.android.common.contact.ContactRepository;
import com.fsck.k9.contacts.ContactPictureLoader;
import com.fsck.k9.contacts.GravatarLoader;
import com.fsck.k9.ui.messageview.DeliveryAddressExtractor;
import com.fsck.k9.contacts.WebsiteIconLoader;
import com.fsck.k9.contacts.bimi.BimiLogoLoader;
import com.fsck.k9.contacts.bimi.BimiRecordKt;
import com.fsck.k9.contacts.bimi.CachedMark;
import com.fsck.k9.contacts.bimi.MarkTrust;
import com.fsck.k9.mailstore.AuthenticationOutcome;
import com.fsck.k9.mailstore.SenderAuthenticationKt;
import com.fsck.k9.helper.ClipboardManager;
import com.fsck.k9.helper.MessageHelper;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Message;
import com.fsck.k9.ui.R;
import com.fsck.k9.ui.helper.BottomBaselineTextView;
import com.fsck.k9.ui.helper.RelativeDateTimeFormatter;
import com.fsck.k9.ui.messageview.DisplayRecipients;
import com.fsck.k9.ui.messageview.DisplayRecipientsExtractor;
import com.fsck.k9.ui.messageview.MessageHeaderClickListener;
import com.fsck.k9.ui.messageview.MessageViewRecipientFormatter;
import com.fsck.k9.ui.messageview.RecipientNamesView;
import com.google.android.material.chip.Chip;
import com.google.android.material.textview.MaterialTextView;
import net.thunderbird.core.android.account.LegacyAccountDto;
import net.thunderbird.core.common.mail.Flag;
import net.thunderbird.core.preference.GeneralSettingsManager;
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListDateTimeFormat;
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListPreferencesManager;
import net.thunderbird.feature.mail.message.reader.api.domain.ReplyAction;
import net.thunderbird.feature.mail.message.reader.api.domain.ReplyActions;
import net.thunderbird.feature.mail.message.reader.api.strategy.ReplyActionStrategy;


public class MessageHeader extends LinearLayout implements OnClickListener, OnLongClickListener {
    private static final int DEFAULT_SUBJECT_LINES = 3;

    private final MessageViewRecipientFormatter recipientFormatter = DI.get(MessageViewRecipientFormatter.class);
    private final MessageListPreferencesManager messageListPreferencesManager =
        DI.get(MessageListPreferencesManager.class);
    private final ReplyActionStrategy<LegacyAccountDto, Message> replyActionStrategy = DI.get(ReplyActionStrategy.class);
    private final MessageHelper messageHelper = DI.get(MessageHelper.class);
    private final FontSizes fontSizes = K9.getFontSizes();

    private Chip accountNameView;
    private BottomBaselineTextView subjectView;
    private ImageView starView;
    private ImageView contactPictureView;
    private MaterialTextView markVerificationView;
    private MaterialTextView deliveredToView;

    /**
     * One thread: these lookups are cached and infrequent, and serialising them keeps a burst of header
     * rebuilds from starting a pile of threads.
     */
    private static final ExecutorService markVerificationExecutor = Executors.newSingleThreadExecutor();

    private String currentSenderDomain;
    private MaterialTextView fromView;
    private ImageView cryptoStatusIcon;
    private RecipientNamesView recipientNamesView;
    private MaterialTextView dateView;
    private ImageView menuPrimaryActionView;
    private View attachmentSummaryContainer;
    private MaterialTextView attachmentSummaryText;
    private MaterialTextView viewAllAttachmentsButton;

    private RelativeDateTimeFormatter relativeDateTimeFormatter;

    private MessageHeaderClickListener messageHeaderClickListener;
    private ReplyActions replyActions;


    public MessageHeader(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (!isInEditMode()) {
            relativeDateTimeFormatter = DI.get(RelativeDateTimeFormatter.class);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        accountNameView = findViewById(R.id.account_name);
        subjectView = findViewById(R.id.subject);
        starView = findViewById(R.id.flagged);
        contactPictureView = findViewById(R.id.contact_picture);
        markVerificationView = findViewById(R.id.mark_verification);
        deliveredToView = findViewById(R.id.delivered_to);
        fromView = findViewById(R.id.from);
        cryptoStatusIcon = findViewById(R.id.crypto_status_icon);
        recipientNamesView = findViewById(R.id.recipients);
        dateView = findViewById(R.id.date);

        fontSizes.setViewTextSize(accountNameView, fontSizes.getMessageViewAccountName());
        fontSizes.setViewTextSize(subjectView, fontSizes.getMessageViewSubject());
        fontSizes.setViewTextSize(dateView, fontSizes.getMessageViewDate());
        fontSizes.setViewTextSize(fromView, fontSizes.getMessageViewSender());

        int recipientTextSize = fontSizes.getMessageViewRecipients();
        if (recipientTextSize != FontSizes.FONT_DEFAULT) {
            recipientNamesView.setTextSize(recipientTextSize);
        }

        subjectView.setOnClickListener(this);
        subjectView.setOnLongClickListener(this);

        menuPrimaryActionView = findViewById(R.id.menu_primary_action);
        menuPrimaryActionView.setOnClickListener(this);

        View menuOverflowView = findViewById(R.id.menu_overflow);
        menuOverflowView.setOnClickListener(this);
        String menuOverflowDescription =
            getContext().getString(androidx.appcompat.R.string.abc_action_menu_overflow_description);
        TooltipCompat.setTooltipText(menuOverflowView, menuOverflowDescription);

        findViewById(R.id.participants_container).setOnClickListener(this);

        attachmentSummaryContainer = findViewById(R.id.attachment_summary_container);
        attachmentSummaryContainer.setOnClickListener(this);
        attachmentSummaryText = findViewById(R.id.attachment_summary_text);
        viewAllAttachmentsButton = findViewById(R.id.view_all_attachments);
        viewAllAttachmentsButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.subject) {
            toggleSubjectViewMaxLines();
        } else if (id == R.id.menu_primary_action) {
            performPrimaryReplyAction();
        } else if (id == R.id.menu_overflow) {
            showOverflowMenu(view);
        } else if (id == R.id.participants_container) {
            messageHeaderClickListener.onParticipantsContainerClick();
        } else if (id == R.id.view_all_attachments || id == R.id.attachment_summary_container) {
            messageHeaderClickListener.onViewAllAttachmentsClick();
        }
    }

    private void performPrimaryReplyAction() {
        ReplyAction defaultAction = replyActions.getDefaultAction();
        if (defaultAction == null) {
            return;
        }

        switch (defaultAction) {
            case REPLY: {
                messageHeaderClickListener.onMenuItemClick(R.id.reply);
                break;
            }
            case REPLY_ALL: {
                messageHeaderClickListener.onMenuItemClick(R.id.reply_all);
                break;
            }
            default: {
                throw new IllegalStateException("Unknown reply action: " + defaultAction);
            }
        }
    }

    private void showOverflowMenu(View view) {
        PopupMenu popupMenu = new PopupMenu(getContext(), view);
        popupMenu.setOnMenuItemClickListener(item -> {
            messageHeaderClickListener.onMenuItemClick(item.getItemId());
            return true;
        });
        popupMenu.inflate(R.menu.single_message_options);
        setAdditionalReplyActions(popupMenu);
        popupMenu.show();
    }

    @Override
    public boolean onLongClick(View view) {
        int id = view.getId();

        if (id == R.id.subject) {
            onAddSubjectToClipboard(subjectView.getText().toString());
        }

        return true;
    }

    private void toggleSubjectViewMaxLines() {
        if (subjectView.getMaxLines() == DEFAULT_SUBJECT_LINES) {
            subjectView.setMaxLines(Integer.MAX_VALUE);
        } else {
            subjectView.setMaxLines(DEFAULT_SUBJECT_LINES);
        }
    }

    private void onAddSubjectToClipboard(String subject) {
        ClipboardManager clipboardManager = DI.get(ClipboardManager.class);
        clipboardManager.setText("subject", subject);

        Toast.makeText(getContext(), createMessageForSubject(), Toast.LENGTH_LONG).show();
    }

    /**
     * Says which of the reader's addresses the message arrived on, when it was not the usual one.
     *
     * Silent for ordinary mail, so the line is a signal rather than a permanent label: seeing it at all means
     * this message came in by a plus address, an alias or a forward.
     */
    private void showDeliveryAddress(Message message, LegacyAccountDto account) {
        String deliveryAddress = DeliveryAddressExtractor.INSTANCE.extractDeliveryAddress(message, account);

        if (deliveryAddress == null) {
            deliveredToView.setVisibility(View.GONE);
        } else {
            deliveredToView.setText(getContext().getString(R.string.message_view_delivered_to, deliveryAddress));
            deliveredToView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Says which verification the sender logo carries, once it is known.
     *
     * The badge on the avatar is small and symbolic; a reader deciding whether to trust a message deserves
     * the claim in words. Looked up off the main thread and only for mail that passed DMARC, because that is
     * the same gate the logo itself is behind.
     *
     * With the sender authentication setting on, the one-word caption is replaced by the picture's source and
     * the checks the receiving server reported - see {@link #showSenderAuthentication}.
     */
    private void showMarkVerification(Address fromAddress, boolean isSenderAuthenticated, Message message) {
        markVerificationView.setVisibility(View.GONE);
        currentSenderDomain = null;

        String domain = senderDomain(fromAddress);
        if (domain == null) {
            return;
        }

        if (isSenderAuthenticationVisible()) {
            showSenderAuthentication(fromAddress, domain, message);
            return;
        }

        if (!isSenderAuthenticated) {
            return;
        }

        currentSenderDomain = domain;
        markVerificationExecutor.execute(() -> {
            Integer label = labelForDomain(domain);
            if (label == null) {
                return;
            }

            // The header is recycled between messages, so the answer is only applied if it is still the
            // sender being shown by the time it arrives.
            post(() -> {
                if (domain.equals(currentSenderDomain)) {
                    markVerificationView.setText(label);
                    markVerificationView.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /**
     * Spells out where the picture beside the sender came from and what the receiving server said it checked,
     * as one line of the shape "Gravatar, DKIM, SPF, DMARC" with a line through anything that did not pass.
     *
     * The one-word caption says how much a logo is worth but not why, and for the weakest tier - a picture
     * anyone can publish - that is exactly the message where the reader most needs the working shown. A check
     * is struck through when the server reported a failure and also when it passed for a domain unrelated to
     * the one in From, because a pass that did not line up is how a lookalike sender collects green ticks.
     *
     * Nothing is shown when no server reported anything: that is not the same as everything failing, and
     * three struck-through checks would say it was.
     */
    private void showSenderAuthentication(Address fromAddress, String domain, Message message) {
        List<AuthenticationOutcome> outcomes =
            SenderAuthenticationKt.authenticationOutcomes(authenticationResults(message), domain);
        if (outcomes.isEmpty()) {
            return;
        }

        String address = fromAddress.getAddress();
        currentSenderDomain = domain;

        markVerificationExecutor.execute(() -> {
            Integer source = sourceLabel(domain, address);

            post(() -> {
                if (domain.equals(currentSenderDomain)) {
                    markVerificationView.setText(authenticationLine(source, outcomes));
                    markVerificationView.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private CharSequence authenticationLine(Integer source, List<AuthenticationOutcome> outcomes) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        String separator = getContext().getString(R.string.message_view_mark_separator);

        if (source != null) {
            line.append(getContext().getString(source));
        }

        for (AuthenticationOutcome outcome : outcomes) {
            if (line.length() > 0) {
                line.append(separator);
            }

            int start = line.length();
            line.append(outcome.getMethod().getLabel());

            if (!outcome.getPassed()) {
                line.setSpan(new StrikethroughSpan(), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        return line;
    }

    /**
     * @return what the picture beside this sender actually is, or null when it is the drawn initial, which
     *   claims nothing and needs no name.
     *
     * Asked in the same order the picture itself is chosen, so the name matches what is on screen. Everything
     * after the contact photo is answered from a cache, so building the line never causes a lookup of its own.
     */
    private Integer sourceLabel(String domain, String address) {
        if (address != null && DI.get(ContactRepository.class).getPhotoUri(address) != null) {
            return R.string.message_view_mark_source_contact_photo;
        }

        CachedMark mark = DI.get(BimiLogoLoader.class).markFor(domain, BimiRecordKt.BIMI_DEFAULT_SELECTOR);
        if (mark != null) {
            return sourceLabelFor(mark.getTrust());
        }

        if (address != null && DI.get(GravatarLoader.class).hasCachedGravatarFor(address)) {
            return R.string.message_view_mark_source_gravatar;
        }

        if (DI.get(WebsiteIconLoader.class).hasCachedIconFor(domain)) {
            return R.string.message_view_mark_source_website_icon;
        }

        return null;
    }

    private int sourceLabelFor(MarkTrust trust) {
        switch (trust) {
            case VERIFIED:
                return R.string.message_view_mark_source_bimi_verified;
            case COMMON:
                return R.string.message_view_mark_source_bimi;
            default:
                return R.string.message_view_mark_source_bimi_unverified;
        }
    }

    private boolean isSenderAuthenticationVisible() {
        return DI.get(GeneralSettingsManager.class)
            .getConfig()
            .getDisplay()
            .getVisualSettings()
            .isMessageViewSenderAuthenticationVisible();
    }

    private String senderDomain(Address fromAddress) {
        if (fromAddress == null) {
            return null;
        }

        String address = fromAddress.getAddress();
        if (address == null) {
            return null;
        }

        int atIndex = address.lastIndexOf('@');
        if (atIndex < 0 || atIndex == address.length() - 1) {
            return null;
        }

        return address.substring(atIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * @return what to call the picture beside this sender, or null when there is nothing to say about it.
     *
     * A website icon only counts if one is already cached, so building the caption never causes a lookup of
     * its own: a caption that went to the network could arrive disagreeing with the picture already drawn.
     */
    private Integer labelForDomain(String domain) {
        CachedMark mark = DI.get(BimiLogoLoader.class).markFor(domain, BimiRecordKt.BIMI_DEFAULT_SELECTOR);
        if (mark != null) {
            return labelFor(mark.getTrust());
        }

        if (DI.get(WebsiteIconLoader.class).hasCachedIconFor(domain)) {
            return R.string.message_view_mark_self_asserted;
        }

        return null;
    }

    private int labelFor(MarkTrust trust) {
        switch (trust) {
            case VERIFIED:
                return R.string.message_view_mark_verified;
            case COMMON:
                return R.string.message_view_mark_common;
            default:
                return R.string.message_view_mark_self_asserted;
        }
    }

    /**
     * Whether the receiving server reported that this message passed DMARC.
     *
     * Read from the message in hand rather than from the stored row, because the message view is the one
     * place that already holds the headers. It gates the brand indicator for the same reason it does in the
     * message list: without it a lookalike domain gets a bank's logo drawn beside its mail.
     */
    private boolean isSenderAuthenticated(Message message) {
        return SenderAuthenticationKt.hasDmarcPass(authenticationResults(message));
    }

    private List<String> authenticationResults(Message message) {
        String[] headers = message.getHeader(SenderAuthenticationKt.authenticationResultsHeaderName());

        return headers == null ? Collections.emptyList() : Arrays.asList(headers);
    }

    public String createMessageForSubject() {
        return getResources().getString(R.string.copy_subject_to_clipboard);
    }

    public void setOnFlagListener(OnClickListener listener) {
        starView.setOnClickListener(listener);
    }

    public void populate(final Message message, final LegacyAccountDto account, boolean showStar,
        boolean showAccountIndicator) {
        if (showAccountIndicator) {
            accountNameView.setVisibility(View.VISIBLE);
            accountNameView.setText(account.getDisplayName());
            accountNameView.setChipBackgroundColor(ColorStateList.valueOf(account.getChipColor()));
        } else {
            accountNameView.setVisibility(View.GONE);
        }

        Address fromAddress = null;
        Address[] fromAddresses = message.getFrom();
        if (fromAddresses.length > 0) {
            fromAddress = fromAddresses[0];
        }

        // The header is recycled, and the line below leaves it hidden unless it has something to say, so it
        // is reset here for the paths that never reach it at all.
        markVerificationView.setVisibility(View.GONE);

        if (messageListPreferencesManager.getConfig().isShowContactPicture()) {
            contactPictureView.setVisibility(View.VISIBLE);
            if (fromAddress != null) {
                ContactPictureLoader contactsPictureLoader = ContactPicture.getContactPictureLoader();
                boolean senderAuthenticated = isSenderAuthenticated(message);
                contactsPictureLoader.setContactPicture(contactPictureView, fromAddress, senderAuthenticated);
                showMarkVerification(fromAddress, senderAuthenticated, message);
            } else {
                contactPictureView.setImageResource(Icons.Outlined.AccountCircle);
            }
        } else {
            contactPictureView.setVisibility(View.GONE);
        }

        // Outside the contact-picture branch above: which address a message arrived on has nothing to do with
        // whether sender pictures are switched on.
        showDeliveryAddress(message, account);

        CharSequence from = messageHelper.getSenderDisplayName(fromAddress);
        fromView.setText(from);

        if (showStar) {
            starView.setVisibility(View.VISIBLE);
            starView.setSelected(message.isSet(Flag.FLAGGED));
        } else {
            starView.setVisibility(View.GONE);
        }

        if (message.getSentDate() != null) {
            dateView.setText(
                relativeDateTimeFormatter.formatDate(
                    message.getSentDate().getTime(),
                    MessageListDateTimeFormat.Contextual
                )
            );
        } else {
            dateView.setText("");
        }

        setRecipientNames(message, account);

        setReplyActions(message, account);

        setVisibility(View.VISIBLE);
    }

    private void setRecipientNames(Message message, LegacyAccountDto account) {
        DisplayRecipientsExtractor displayRecipientsExtractor = new DisplayRecipientsExtractor(recipientFormatter,
            recipientNamesView.getMaxNumberOfRecipientNames());

        DisplayRecipients displayRecipients = displayRecipientsExtractor.extractDisplayRecipients(message, account);

        recipientNamesView.setRecipients(displayRecipients.getRecipientNames(),
            displayRecipients.getNumberOfRecipients());
    }

    private void setReplyActions(Message message, LegacyAccountDto account) {
        ReplyActions replyActions = replyActionStrategy.getReplyActions(account, message);
        this.replyActions = replyActions;

        setDefaultReplyAction(replyActions.getDefaultAction());
    }

    private void setDefaultReplyAction(ReplyAction defaultAction) {
        if (defaultAction == null) {
            menuPrimaryActionView.setVisibility(View.GONE);
        } else {
            int replyIconResource = getReplyImageResource(defaultAction);
            menuPrimaryActionView.setImageResource(replyIconResource);

            String replyActionName = getReplyActionName(defaultAction);
            TooltipCompat.setTooltipText(menuPrimaryActionView, replyActionName);

            menuPrimaryActionView.setVisibility(View.VISIBLE);
        }
    }

    @DrawableRes
    private int getReplyImageResource(@NonNull ReplyAction replyAction) {
        switch (replyAction) {
            case REPLY: {
                return Icons.Outlined.Reply;
            }
            case REPLY_ALL: {
                return Icons.Outlined.ReplyAll;
            }
            default: {
                throw new IllegalStateException("Unknown reply action: " + replyAction);
            }
        }
    }

    @NonNull
    private String getReplyActionName(@NonNull ReplyAction replyAction) {
        Context context = getContext();
        switch (replyAction) {
            case REPLY: {
                return context.getString(R.string.reply_action);
            }
            case REPLY_ALL: {
                return context.getString(R.string.reply_all_action);
            }
            default: {
                throw new IllegalStateException("Unknown reply action: " + replyAction);
            }
        }
    }

    private void setAdditionalReplyActions(PopupMenu popupMenu) {
        List<ReplyAction> additionalActions = replyActions.getAdditionalActions();
        if (!additionalActions.contains(ReplyAction.REPLY)) {
            popupMenu.getMenu().removeItem(R.id.reply);
        }
        if (!additionalActions.contains(ReplyAction.REPLY_ALL)) {
            popupMenu.getMenu().removeItem(R.id.reply_all);
        }
    }

    public void setSubject(@NonNull String subject) {
        subjectView.setText(subject);
    }

    public void hideCryptoStatus() {
        cryptoStatusIcon.setVisibility(View.GONE);
    }

    public void setCryptoStatusLoading() {
        setCryptoDisplayStatus(MessageCryptoDisplayStatus.LOADING);
    }

    public void setCryptoStatusDisabled() {
        setCryptoDisplayStatus(MessageCryptoDisplayStatus.DISABLED);
    }

    public void setCryptoStatus(MessageCryptoDisplayStatus displayStatus) {
        setCryptoDisplayStatus(displayStatus);
    }

    private void setCryptoDisplayStatus(MessageCryptoDisplayStatus displayStatus) {
        int color = ThemeUtils.getStyledColor(getContext(), displayStatus.getColorAttr());
        cryptoStatusIcon.setEnabled(displayStatus.isEnabled());
        cryptoStatusIcon.setVisibility(View.VISIBLE);
        cryptoStatusIcon.setImageResource(displayStatus.getStatusIconRes());
        cryptoStatusIcon.setColorFilter(color);
    }

    public void setMessageHeaderClickListener(MessageHeaderClickListener messageHeaderClickListener) {
        this.messageHeaderClickListener = messageHeaderClickListener;
    }

    public void setAttachmentSummary(@NonNull String summaryText, @NonNull String viewButtonText) {
        attachmentSummaryText.setText(summaryText);
        viewAllAttachmentsButton.setText(viewButtonText);
        attachmentSummaryContainer.setVisibility(View.VISIBLE);
    }

    public void hideAttachmentSummary() {
        attachmentSummaryContainer.setVisibility(View.GONE);
    }
}
