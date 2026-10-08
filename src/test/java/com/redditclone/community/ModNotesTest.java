package com.redditclone.community;

import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Moderator notes: persistence in PostgreSQL, visibility to every moderator of the SAME community (and nobody else), community isolation,
// and the community-wide "recent notes" list the Notes tab loads on open. Shared Spring context, rolled-back transactions.
@SpringBootTest
@Transactional
class ModNotesTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper json;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID modA;      // moderator A (the community's creator/owner)
    private UUID modB;      // moderator B (limited permissions: reading and writing notes needs only "is a moderator")
    private UUID normal;
    private UUID subject;   // the user the notes are about
    private UUID subject2;
    private Community community;
    private Community other;
    private UUID otherOwner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        modA = fx.user("mn_moda");
        modB = fx.user("mn_modb");
        normal = fx.user("mn_normal");
        subject = fx.user("mn_subject");
        subject2 = fx.user("mn_subject2");
        otherOwner = fx.user("mn_otherowner");
        community = fx.community(modA);
        other = fx.community(otherOwner);
        service.addModerator(modA, community.getId(), modB, CommunityModerator.PERM_REMOVE_CONTENT);
        em.flush();
    }

    // ---------- helpers ----------

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private MockHttpServletRequestBuilder req(HttpMethod m, String path, UUID as, String body) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(m, path).contentType(MediaType.APPLICATION_JSON);
        if (as != null) b = b.header("Authorization", token(as));
        return body == null ? b : b.content(body);
    }

    private String path(Community c) { return "/r/" + c.getName() + "/mod/notes"; }

    private JsonNode addNote(UUID as, Community c, UUID about, String text) throws Exception {
        MvcResult r = mvc.perform(req(HttpMethod.POST, path(c), as, "{\"userId\":\"" + about + "\",\"note\":\"" + text + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode list(UUID as, Community c, UUID aboutOrNull) throws Exception {
        String url = path(c) + (aboutOrNull == null ? "" : "?userId=" + aboutOrNull);
        MvcResult r = mvc.perform(req(HttpMethod.GET, url, as, null)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("note").asString()));
        return out;
    }

    private int rows(Community c) {
        return jdbc.queryForObject("SELECT count(*) FROM mod_notes WHERE community_id = ?", Integer.class, c.getId());
    }

    // ---------- persistence and visibility between moderators ----------

    @Test
    void aNoteIsWrittenToPostgresAndTheOtherModeratorSeesIt() throws Exception {
        JsonNode created = addNote(modA, community, subject, "warned for spam");
        assertEquals(1, rows(community), "the note is a real mod_notes row");
        assertEquals(modA.toString(), jdbc.queryForObject("SELECT author_id::text FROM mod_notes WHERE id = ?::uuid", String.class, created.get("id").asString()));

        JsonNode seenByB = list(modB, community, subject);
        assertEquals(List.of("warned for spam"), texts(seenByB));
        assertEquals(users.findById(modA).orElseThrow().getUsername(), seenByB.get(0).get("authorUsername").asString(), "B sees who wrote it");
        assertEquals(users.findById(subject).orElseThrow().getUsername(), seenByB.get(0).get("subjectUsername").asString());
    }

    @Test
    void theNoteSurvivesAFreshAuthenticatedSession() throws Exception {
        addNote(modA, community, subject, "persisted note");
        em.clear(); // nothing is cached anywhere: the next requests carry brand-new tokens and read the database
        for (int i = 0; i < 3; i++) { // each request mints a fresh JWT, like logging out and back in
            assertEquals(List.of("persisted note"), texts(list(modA, community, subject)));
            assertEquals(List.of("persisted note"), texts(list(modA, community, null)));
        }
    }

    // ---------- the community-wide "recent notes" list the Notes tab loads on open ----------

    @Test
    void withoutAUserTheListIsTheCommunitysRecentNotesNewestFirstWithSubjectNames() throws Exception {
        jdbc.update("INSERT INTO mod_notes (id, community_id, user_id, author_id, note, created_at) VALUES (?, ?, ?, ?, 'oldest', now() - interval '3 hours')",
                UUID.randomUUID(), community.getId(), subject, modA);
        jdbc.update("INSERT INTO mod_notes (id, community_id, user_id, author_id, note, created_at) VALUES (?, ?, ?, ?, 'middle', now() - interval '2 hours')",
                UUID.randomUUID(), community.getId(), subject2, modB);
        addNote(modB, community, subject, "newest");

        JsonNode all = list(modA, community, null);
        assertEquals(List.of("newest", "middle", "oldest"), texts(all));
        assertEquals(users.findById(subject2).orElseThrow().getUsername(), all.get(1).get("subjectUsername").asString());
        assertEquals(users.findById(modB).orElseThrow().getUsername(), all.get(1).get("authorUsername").asString(), "notes by any moderator are listed");
        assertEquals(List.of("newest", "oldest"), texts(list(modA, community, subject)), "with a user, only that user's notes");
    }

    @Test
    void theRecentListIsCappedAt100() throws Exception {
        for (int i = 0; i < 105; i++) {
            jdbc.update("INSERT INTO mod_notes (id, community_id, user_id, author_id, note, created_at) VALUES (?, ?, ?, ?, ?, now() - (? * interval '1 minute'))",
                    UUID.randomUUID(), community.getId(), subject, modA, "n" + i, i);
        }
        assertEquals(100, list(modA, community, null).size());
    }

    // ---------- authorization ----------

    @Test
    void ordinaryUsersAndAnonymousCannotReadWriteOrDeleteNotes() throws Exception {
        JsonNode note = addNote(modA, community, subject, "secret");
        String body = "{\"userId\":\"" + subject + "\",\"note\":\"x\"}";
        for (String url : List.of(path(community), path(community) + "?userId=" + subject)) {
            mvc.perform(req(HttpMethod.GET, url, normal, null)).andExpect(status().isForbidden());
            mvc.perform(req(HttpMethod.GET, url, subject, null)).andExpect(status().isForbidden()); // not even the subject of the notes
            mvc.perform(req(HttpMethod.GET, url, null, null)).andExpect(status().isUnauthorized());
        }
        mvc.perform(req(HttpMethod.POST, path(community), normal, body)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.POST, path(community), null, body)).andExpect(status().isUnauthorized());
        mvc.perform(req(HttpMethod.DELETE, path(community) + "/" + note.get("id").asString(), normal, null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.DELETE, path(community) + "/" + note.get("id").asString(), null, null)).andExpect(status().isUnauthorized());
        assertEquals(1, rows(community), "nothing was added or removed by the refused calls");
    }

    @Test
    void aDeletedCommunityHidesItsNotes() throws Exception {
        addNote(modA, community, subject, "before");
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", modA, community.getId());
        em.flush();
        em.clear();
        mvc.perform(req(HttpMethod.GET, path(community), modA, null)).andExpect(status().isNotFound());
        assertEquals(1, rows(community), "the rows are kept");
    }

    // ---------- community isolation ----------

    @Test
    void notesStayInsideTheirCommunity() throws Exception {
        addNote(modA, community, subject, "only in community A");
        addNote(otherOwner, other, subject, "only in community B");

        assertEquals(List.of("only in community A"), texts(list(modA, community, subject)));
        assertEquals(List.of("only in community B"), texts(list(otherOwner, other, subject)));
        assertEquals(List.of("only in community A"), texts(list(modA, community, null)));
        assertEquals(List.of("only in community B"), texts(list(otherOwner, other, null)));
        // a moderator of B has no access to A's notes at all, and vice versa
        mvc.perform(req(HttpMethod.GET, path(community), otherOwner, null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.GET, path(other) + "?userId=" + subject, modA, null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.POST, path(community), otherOwner, "{\"userId\":\"" + subject + "\",\"note\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void aNoteCannotBeDeletedThroughAnotherCommunity() throws Exception {
        JsonNode note = addNote(modA, community, subject, "keep me");
        mvc.perform(req(HttpMethod.DELETE, path(other) + "/" + note.get("id").asString(), otherOwner, null)).andExpect(status().isNotFound());
        assertEquals(1, rows(community));
    }

    // ---------- writing and deleting ----------

    @Test
    void anyModeratorMayWriteButOnlyTheAuthorOrAManagerMayDelete() throws Exception {
        JsonNode byB = addNote(modB, community, subject, "written by B");
        JsonNode byA = addNote(modA, community, subject, "written by A");
        // B (no manage-moderators permission) cannot delete A's note, but can delete their own
        mvc.perform(req(HttpMethod.DELETE, path(community) + "/" + byA.get("id").asString(), modB, null)).andExpect(status().isForbidden());
        mvc.perform(req(HttpMethod.DELETE, path(community) + "/" + byB.get("id").asString(), modB, null)).andExpect(status().isOk());
        // the owner may delete anyone's
        addNote(modB, community, subject, "another by B");
        UUID again = UUID.fromString(list(modA, community, subject).get(0).get("id").asString());
        mvc.perform(req(HttpMethod.DELETE, path(community) + "/" + again, modA, null)).andExpect(status().isOk());
        assertEquals(List.of("written by A"), texts(list(modA, community, subject)));
    }

    @Test
    void invalidNotesAreRejected() throws Exception {
        mvc.perform(req(HttpMethod.POST, path(community), modA, "{\"userId\":\"" + subject + "\",\"note\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(req(HttpMethod.POST, path(community), modA, "{\"userId\":\"" + subject + "\",\"note\":\"" + "x".repeat(1001) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(req(HttpMethod.POST, path(community), modA, "{\"userId\":\"" + UUID.randomUUID() + "\",\"note\":\"ghost\"}")).andExpect(status().isNotFound());
        assertTrue(rows(community) == 0);
    }
}
