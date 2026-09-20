package com.jmip.dto.assistant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * What the last turn was about, so "what about Bengaluru?" has something to attach to.
 *
 * <p>It holds one turn, not a transcript. That is a deliberate ceiling rather than a
 * simplification: an unbounded history is an unbounded prompt, and the questions this
 * assistant answers refer back one step or not at all.
 *
 * <p>It is returned to the browser and sent back with the next question, so the server
 * keeps no per-user state and needs no session store. The consequence is that it is
 * client-supplied input like any other, and it is validated and resolved exactly the same
 * way — nothing in it reaches a query without passing the resolver first.
 *
 * @param previousQuestion what was asked last, for the model's benefit only
 * @param previousIntent   what that resolved to
 * @param previousEntities the entities that were in play
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationContext(
        @Size(max = 500) String previousQuestion,
        AssistantIntent previousIntent,
        @Valid AssistantEntities previousEntities) {

    public static ConversationContext empty() {
        return new ConversationContext(null, null, null);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return previousIntent == null && previousQuestion == null;
    }

    @JsonIgnore
    public AssistantEntities entitiesOrEmpty() {
        return previousEntities == null ? AssistantEntities.empty() : previousEntities;
    }
}
