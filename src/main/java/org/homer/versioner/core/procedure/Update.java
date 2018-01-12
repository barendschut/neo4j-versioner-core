package org.homer.versioner.core.procedure;

import org.homer.versioner.core.core.CoreProcedure;
import org.homer.versioner.core.exception.VersionerCoreException;
import org.homer.versioner.core.output.NodeOutput;
import org.neo4j.graphdb.*;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.homer.versioner.core.Utility.*;

/**
 * Update class, it contains all the Procedures needed to update Entities' States
 */
public class Update extends CoreProcedure {

    @Procedure(value = "graph.versioner.update", mode = Mode.WRITE)
    @Description("graph.versioner.update(entity, {key:value,...}, additionalLabel, date) - Add a new State to the given Entity.")
    public Stream<NodeOutput> update(
            @Name("entity") Node entity,
            @Name(value = "stateProps", defaultValue = "{}") Map<String, Object> stateProps,
            @Name(value = "additionalLabel", defaultValue = "") String additionalLabel,
            @Name(value = "date", defaultValue = "0") long date) {

        // Creating the new State
        List<String> labelNames = new ArrayList<>(Collections.singletonList(STATE_LABEL));
        if (!additionalLabel.isEmpty()) {
            labelNames.add(additionalLabel);
        }
        Node result = setProperties(db.createNode(asLabels(labelNames)), stateProps);

        long instantDate = (date == 0) ? Calendar.getInstance().getTimeInMillis() : date;

        // Getting the CURRENT rel if it exist
        Spliterator<Relationship> currentRelIterator = entity.getRelationships(RelationshipType.withName(CURRENT_TYPE), Direction.OUTGOING).spliterator();
        StreamSupport.stream(currentRelIterator, false).forEach(currentRel -> {
            Node currentState = currentRel.getEndNode();
            Long currentDate = (Long) currentRel.getProperty("date");

            // Creating PREVIOUS relationship between the current and the new State
            result.createRelationshipTo(currentState, RelationshipType.withName(PREVIOUS_TYPE)).setProperty(DATE_PROP, currentDate);

            // Updating the HAS_STATE rel for the current node, adding endDate
            currentState.getRelationships(RelationshipType.withName(HAS_STATE_TYPE), Direction.INCOMING)
                    .forEach(hasStatusRel -> hasStatusRel.setProperty(END_DATE_PROP, instantDate));

            // Refactoring current relationship and adding the new ones
            currentRel.delete();
        });

        // Connecting the new current state to the Entity
        addCurrentState(result, entity, instantDate);

        log.info(LOGGER_TAG + "Updated Entity with id {}, adding a State with id {}", entity.getId(), result.getId());

        return Stream.of(new NodeOutput(result));
    }

    @Procedure(value = "graph.versioner.patch", mode = Mode.WRITE)
    @Description("graph.versioner.patch(entity, {key:value,...}, additionalLabel, date) - Add a new State to the given Entity, starting from the previous one. It will update all the properties, not asLabels.")
    public Stream<NodeOutput> patch(
            @Name("entity") Node entity,
            @Name(value = "stateProps", defaultValue = "{}") Map<String, Object> stateProps,
            @Name(value = "additionalLabel", defaultValue = "") String additionalLabel,
            @Name(value = "date", defaultValue = "0") long date) {

        List<String> labelNames = getStateLabels(additionalLabel);
        long instantDate = defaultToNow(date);

        Node newState = getCurrentRelationship(entity)
                .map(currentRelationship -> createPatchedState(stateProps, labelNames, instantDate, currentRelationship))
                .orElseGet(() -> {
                    Node result = setProperties(db.createNode(asLabels(labelNames)), stateProps);
                    addCurrentState(result, entity, instantDate);
                    return result;
                });

        log.info(LOGGER_TAG + "Patched Entity with id {}, adding a State with id {}", entity.getId(), newState.getId());

        return Stream.of(new NodeOutput(newState));
    }

    @Procedure(value = "graph.versioner.patch.from", mode = Mode.WRITE)
    @Description("graph.versioner.patch.from(entity, state, date) - Add a new State to the given Entity, starting from the given one. It will update all the properties, not asLabels.")
    public Stream<NodeOutput> patchFrom(
            @Name("entity") Node entity,
            @Name("state") Node state,
            @Name(value = "date", defaultValue = "0") long date) {

        long instantDate = defaultToNow (date);
        List<String> labels = streamOfIterable(state.getLabels()).map(Label::name).collect(Collectors.toList());

        checkRelationship(entity, state);

        Node newState = getCurrentRelationship(entity)
                .map(currentRelationship -> createPatchedState(state.getAllProperties(), labels, instantDate, currentRelationship))
                .orElseThrow(() -> new VersionerCoreException("Can't find any current State node for the given entity."));

        log.info(LOGGER_TAG + "Patched Entity with id {}, adding a State with id {}", entity.getId(), newState.getId());
        return Stream.of(new NodeOutput(newState));
    }

    private Node createPatchedState(Map<String, Object> stateProps, List<String> labels, long instantDate, Relationship currentRelationship) {

        Node currentState = currentRelationship.getEndNode();
        Long currentDate = (Long) currentRelationship.getProperty("date");
        Node entity = currentRelationship.getStartNode();

        // Patching the current node into the new one.
        Map<String, Object> patchedProps = currentState.getAllProperties();
        patchedProps.putAll(stateProps);
        Node newStateToElaborate = setProperties(db.createNode(asLabels(labels)), patchedProps);

        // Updating CURRENT state
        final Node result = currentStateUpdate(entity, instantDate, currentRelationship, currentState, currentDate, newStateToElaborate);

        //Copy all the relationships
        streamOfIterable(currentState.getRelationships(Direction.OUTGOING))
                .filter(rel -> rel.getEndNode().hasLabel(Label.label("R")))
                .forEach(rel -> RelationshipProcedure.createRelationship(result, rel.getEndNode(), rel.getType().name()));

        return result;
    }
}
