package com.redditclone.chat;

import com.redditclone.auth.AuthService;
import com.redditclone.block.BlockService;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final BlockService blocks;
    private final ChatRoomRepository chatRooms;
    private final ChatRoomParticipantRepository participants;
    private final ChatMessageRepository messages;
    private final AuthService authService;
    private final Sanitizer sanitizer;
    private final UuidV7Generator ids;
    private final NamedParameterJdbcTemplate jdbc;
    private final int maxMessageLength;

    public ChatService(ChatRoomRepository chatRooms, ChatRoomParticipantRepository participants,
                        ChatMessageRepository messages, AuthService authService, Sanitizer sanitizer,
                        UuidV7Generator ids, NamedParameterJdbcTemplate jdbc,
                        BlockService blocks,
                        @Value("${app.chat.max-message-length}") int maxMessageLength) {
        this.chatRooms = chatRooms;
        this.participants = participants;
        this.messages = messages;
        this.authService = authService;
        this.sanitizer = sanitizer;
        this.ids = ids;
        this.jdbc = jdbc;
        this.blocks = blocks;
        this.maxMessageLength = maxMessageLength;
    }

    @Transactional
    public ChatRoom findOrCreateRoom(UUID callerId, List<String> otherUsernames) {
        Set<String> usernameSet = new HashSet<>(otherUsernames);
        Map<String, UUID> resolved = authService.findUserIdsByUsernames(usernameSet);
        if (resolved.size() != usernameSet.size()) {
            throw new NotFoundException("no such user");
        }
        Set<UUID> participantIds = new HashSet<>(resolved.values());
        participantIds.add(callerId);
        if (participantIds.size() < 2) {
            throw new BadRequestException("a chat room needs at least one other participant");
        }

        // Postgres defaults to READ COMMITTED, so a plain findExactRoom-then-insert is a TOCTOU race: two
        // concurrent "start a chat with X" calls from the same caller could both see no existing room and
        // both create one, fragmenting the conversation into two rooms. Same fix as VoteService's
        // lockVoteKey — a transaction-scoped advisory lock keyed on the sorted participant set serializes
        // concurrent calls for the same set without needing a row to lock.
        lockRoomKey(participantIds);

        Optional<UUID> existingRoomId = findExactRoom(participantIds);
        if (existingRoomId.isPresent()) {
            return chatRooms.findById(existingRoomId.get())
                    .orElseThrow(() -> new NotFoundException("no such chat room"));
        }

        // Only checked on the brand-new-room path above — reopening an existing room (the branch just
        // above) is never affected, so a conversation that already exists keeps working even if one side
        // later opts into this restriction.
        for (UUID targetId : resolved.values()) {
            blocks.requireNotBlockedBy(targetId, callerId);
            if (!targetId.equals(callerId) && authService.restrictsChatToKnown(targetId)
                    && !alreadyKnowsEachOther(callerId, targetId)) {
                throw new ForbiddenException("this user only accepts messages from people they've already talked to");
            }
        }

        ChatRoom room = new ChatRoom();
        room.setId(ids.nextId());
        chatRooms.save(room);
        for (UUID participantId : participantIds) {
            participants.save(new ChatRoomParticipant(room.getId(), participantId));
        }
        return room;
    }

    public List<ChatRoomSummary> listRooms(UUID userId) {
        List<ChatRoomParticipant> mine = participants.findByUserId(userId);
        if (mine.isEmpty()) {
            return List.of();
        }
        Set<UUID> roomIds = mine.stream().map(ChatRoomParticipant::getRoomId).collect(Collectors.toSet());

        List<Map<String, Object>> participantRows = jdbc.queryForList("""
                SELECT room_id, user_id FROM chat_room_participants
                WHERE room_id IN (:roomIds) AND user_id <> :userId
                """, new MapSqlParameterSource().addValue("roomIds", roomIds).addValue("userId", userId));
        Map<UUID, List<UUID>> otherIdsByRoom = new HashMap<>();
        Set<UUID> allOtherIds = new HashSet<>();
        for (Map<String, Object> row : participantRows) {
            UUID roomId = (UUID) row.get("room_id");
            UUID otherId = (UUID) row.get("user_id");
            otherIdsByRoom.computeIfAbsent(roomId, k -> new ArrayList<>()).add(otherId);
            allOtherIds.add(otherId);
        }
        Map<UUID, String> usernamesById = authService.findUsernamesByIds(allOtherIds);

        List<Map<String, Object>> lastMessageRows = jdbc.queryForList("""
                SELECT DISTINCT ON (room_id) room_id, sender_id, body, created_at
                FROM chat_messages WHERE room_id IN (:roomIds)
                ORDER BY room_id, created_at DESC, id DESC
                """, new MapSqlParameterSource("roomIds", roomIds));
        Map<UUID, Map<String, Object>> lastMessageByRoom = new HashMap<>();
        for (Map<String, Object> row : lastMessageRows) {
            lastMessageByRoom.put((UUID) row.get("room_id"), row);
        }

        // Unread = messages in that room, not sent by me, newer than my own last_read_at for that room —
        // joined directly against chat_room_participants' per-(room,user) last_read_at rather than
        // computed in Java, since that's exactly the per-room cutoff this column exists for.
        List<Map<String, Object>> unreadRows = jdbc.queryForList("""
                SELECT m.room_id AS room_id, COUNT(*) AS unread
                FROM chat_messages m
                JOIN chat_room_participants p ON p.room_id = m.room_id AND p.user_id = :userId
                WHERE m.room_id IN (:roomIds) AND m.sender_id <> :userId
                  AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)
                GROUP BY m.room_id
                """, new MapSqlParameterSource().addValue("roomIds", roomIds).addValue("userId", userId));
        Map<UUID, Long> unreadByRoom = new HashMap<>();
        for (Map<String, Object> row : unreadRows) {
            unreadByRoom.put((UUID) row.get("room_id"), ((Number) row.get("unread")).longValue());
        }

        List<ChatRoomSummary> summaries = new ArrayList<>();
        for (UUID roomId : roomIds) {
            List<String> others = otherIdsByRoom.getOrDefault(roomId, List.of()).stream()
                    .map(id -> usernamesById.getOrDefault(id, "[deleted]"))
                    .toList();
            Map<String, Object> last = lastMessageByRoom.get(roomId);
            String lastBody = last == null ? null : (String) last.get("body");
            UUID lastSender = last == null ? null : (UUID) last.get("sender_id");
            Instant lastAt = last == null ? null : ((Timestamp) last.get("created_at")).toInstant();
            summaries.add(new ChatRoomSummary(roomId, others, lastBody, lastSender, lastAt,
                    unreadByRoom.getOrDefault(roomId, 0L)));
        }
        summaries.sort(Comparator.comparing(ChatRoomSummary::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return summaries;
    }

    public List<ChatMessage> history(UUID userId, UUID roomId, Instant cursorCreatedAt, UUID cursorId, int limit) {
        requireParticipant(userId, roomId);
        List<ChatMessage> page = messages.findPage(roomId, cursorCreatedAt, cursorId, Pageable.ofSize(limit));
        attachSenderUsernames(page);
        return page;
    }

    // Same @Transient attach-plus-batched-lookup pattern as PostService.attachAuthorUsername — a message
    // only carries a bare senderId column, and a conversation (especially a group room) needs to know who
    // said what without the client guessing from the room's otherParticipants list.
    private void attachSenderUsernames(List<ChatMessage> page) {
        if (page.isEmpty()) {
            return;
        }
        Set<UUID> senderIds = page.stream().map(ChatMessage::getSenderId).collect(Collectors.toSet());
        Map<UUID, String> usernames = authService.findUsernamesByIds(senderIds);
        for (ChatMessage m : page) {
            m.setSenderUsername(usernames.get(m.getSenderId()));
        }
    }

    @Transactional
    public ChatMessage send(UUID senderId, UUID roomId, String body) {
        if (!authService.isActive(senderId)) {
            throw new ForbiddenException("account is not active");
        }
        requireParticipant(senderId, roomId);
        String sanitized = sanitizer.sanitize(body);
        if (sanitized == null || sanitized.isBlank()) {
            throw new BadRequestException("body is required");
        }
        if (sanitized.length() > maxMessageLength) {
            throw new BadRequestException("body exceeds max length of " + maxMessageLength);
        }
        ChatMessage m = new ChatMessage();
        m.setId(ids.nextId());
        m.setRoomId(roomId);
        m.setSenderId(senderId);
        m.setBody(sanitized);
        ChatMessage saved = messages.save(m);
        // Same single-id-via-batched-call shape as PostService.attachAuthorUsername — resolved here, not
        // in ChatStompHandler, so both the live-pushed message and a future REST send path get it for free.
        saved.setSenderUsername(authService.findUsernamesByIds(Set.of(senderId)).get(senderId));
        return saved;
    }

    @Transactional
    public void markRead(UUID userId, UUID roomId) {
        ChatRoomParticipant p = participants.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new NotFoundException("no such chat room"));
        p.setLastReadAt(Instant.now());
        participants.save(p);
    }

    // Read by ChatStompHandler to know who else to push a just-sent message to.
    public List<UUID> otherParticipantIds(UUID roomId, UUID excludingUserId) {
        return participants.findByRoomId(roomId).stream()
                .map(ChatRoomParticipant::getUserId)
                .filter(id -> !id.equals(excludingUserId))
                .toList();
    }

    private void requireParticipant(UUID userId, UUID roomId) {
        if (!participants.existsByRoomIdAndUserId(roomId, userId)) {
            throw new NotFoundException("no such chat room");
        }
    }

    private void lockRoomKey(Set<UUID> participantIds) {
        String key = participantIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(:key))", new MapSqlParameterSource("key", key), Object.class);
    }

    // A room "with exactly this participant set" — HAVING both the room's total size and the count of
    // matching ids equal the requested size means every participant is in the set AND nothing extra is —
    // reopens an existing thread instead of fragmenting it into duplicates, same behavior every real chat
    // product (including Reddit Chat) has when you message someone you already have a thread with.
    private Optional<UUID> findExactRoom(Set<UUID> participantIds) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT room_id FROM chat_room_participants
                GROUP BY room_id
                HAVING COUNT(*) = :size AND COUNT(*) FILTER (WHERE user_id IN (:ids)) = :size
                """, new MapSqlParameterSource().addValue("size", participantIds.size()).addValue("ids", participantIds));
        return rows.isEmpty() ? Optional.empty() : Optional.of((UUID) rows.get(0).get("room_id"));
    }

    // "Already talked to" for the restrictChatToKnown privacy gate — any room the two already share, not
    // necessarily one with this exact participant set (unlike findExactRoom, which is about dedup for the
    // room being created right now).
    private boolean alreadyKnowsEachOther(UUID a, UUID b) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM chat_room_participants p1
                    JOIN chat_room_participants p2 ON p1.room_id = p2.room_id
                    WHERE p1.user_id = :a AND p2.user_id = :b
                )
                """, new MapSqlParameterSource().addValue("a", a).addValue("b", b), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }
}
