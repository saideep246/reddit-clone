package com.redditclone.community;

import com.redditclone.comment.CommentRepository;
import com.redditclone.post.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Guard, no Spring context: every query that spans communities must carry the deleted-community predicate. Compares with ALL
// whitespace removed, so reformatting a query cannot break it, while dropping or weakening the predicate does.
class CommunityDeletedQueryGuardTest {

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String query(Class<?> repo, String method) {
        for (Method m : repo.getMethods()) {
            if (m.getName().equals(method) && m.isAnnotationPresent(Query.class)) {
                return squash(m.getAnnotation(Query.class).value());
            }
        }
        throw new AssertionError("no @Query method " + repo.getSimpleName() + "." + method);
    }

    @Test
    void siteWidePostQueriesCarryTheSharedVisibilityPredicate() {
        String predicate = squash(PostRepository.COMMUNITY_VISIBLE_TO_VIEWER);
        for (String m : List.of("findNewAllPage", "findHotAllPage", "findTopAllPage", "findRisingAllPage",
                "findControversialAllPage", "findByAuthorId")) {
            assertTrue(query(PostRepository.class, m).contains(predicate), m + " lost the community-visibility predicate");
        }
        assertTrue(query(PostRepository.class, "findSavedPage").contains(squash(PostRepository.COMMUNITY_NOT_DELETED)));
    }

    @Test
    void theSharedPredicateKeepsDeletedAtOutsideThePrivateOrGroup() {
        String p = squash(PostRepository.COMMUNITY_VISIBLE_TO_VIEWER);
        int deleted = p.indexOf("cm.deletedAtISNULL");
        int group = p.indexOf("AND(cm.type<>'private'OR");
        assertTrue(deleted > 0 && group > deleted, "deleted_at must be ANDed before, not inside, the private/member OR group");
        assertTrue(p.contains("cm.id=p.communityId"), "must require the community to exist");
    }

    @Test
    void nativeSearchesCarryTheDeletedPredicateBeforeThePrivateGroup() {
        String posts = query(PostRepository.class, "searchAllIds");
        assertTrue(posts.contains("ANDc.deleted_atISNULLAND(c.type<>'private'OR"), "searchAllIds");
        String comments = query(CommentRepository.class, "searchIds");
        assertTrue(comments.contains("ANDcm.deleted_atISNULLAND(CAST"), "comment searchIds");
        assertTrue(query(CommunityRepository.class, "searchByName").contains("WHEREdeleted_atISNULLAND"), "searchByName");
    }

    @Test
    void ownCommentsQueryExcludesDeletedCommunitiesSeparatelyFromTheOrphanFailOpenClause() {
        String q = query(CommentRepository.class, "findByAuthorId");
        assertTrue(q.contains("ANDNOTEXISTS(SELECT1FROMPostp,Communitycm"
                + "WHEREp.id=c.postIdANDcm.id=p.communityIdANDcm.deletedAtISNOTNULL)"));
    }

    @Test
    void everyCommunityListingQueryFiltersDeletedRows() {
        int checked = 0;
        for (Method m : CommunityRepository.class.getMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null || !List.class.isAssignableFrom(m.getReturnType())) { // listings/searches only, not counters or markDeleted
                continue;
            }
            String s = squash(q.value());
            assertTrue(s.contains("deletedAtISNULL") || s.contains("deleted_atISNULL"), m.getName() + " does not filter deleted communities");
            checked++;
        }
        assertTrue(checked >= 3, "expected at least findNewPage, findPopularPage, searchByName");
    }

    @Test
    void noPostQueryKeepsTheOldPrivateOnlyClauseWithoutTheDeletedCheck() {
        // a future site-wide query copied from before this step would contain the bare private test but no deletion check
        for (Method m : PostRepository.class.getMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null) {
                continue;
            }
            String s = squash(q.value());
            if (s.contains("cm.type='private'") || s.contains("c.type<>'private'")) {
                assertTrue(s.contains("deletedAtISNULL") || s.contains("deleted_atISNULL"), m.getName() + " checks private but not deleted");
            }
        }
    }
}
