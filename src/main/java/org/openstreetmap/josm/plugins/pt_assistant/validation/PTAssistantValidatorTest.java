// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.pt_assistant.validation;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SelectCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmDataManager;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.plugins.pt_assistant.PTAssistantPlugin;
import org.openstreetmap.josm.plugins.pt_assistant.actions.FixTask;
import org.openstreetmap.josm.plugins.pt_assistant.actions.IncompleteMembersDownloadThread;
import org.openstreetmap.josm.plugins.pt_assistant.data.PTRouteDataManager;
import org.openstreetmap.josm.plugins.pt_assistant.data.PTRouteSegment;
import org.openstreetmap.josm.plugins.pt_assistant.data.PTStop;
import org.openstreetmap.josm.plugins.pt_assistant.data.PTWay;
import org.openstreetmap.josm.plugins.pt_assistant.gui.PTAssistantLayerManager;
import org.openstreetmap.josm.plugins.pt_assistant.utils.PTProperties;
import org.openstreetmap.josm.plugins.pt_assistant.utils.RouteUtils;
import org.openstreetmap.josm.plugins.pt_assistant.utils.StopToWayAssigner;
import org.openstreetmap.josm.plugins.pt_assistant.utils.StopUtils;
import org.openstreetmap.josm.tools.Logging;

/**
 * Public Transport Assistant tests.
 * Check if route relations are compatible with public transport version 2
 *
 * @author darya
 */
public class PTAssistantValidatorTest extends Test {

    public static final int ERROR_CODE_FROM_TO_ROUTE_TAG = 3701;
    public static final int ERROR_CODE_FIRST_LAST_STOP_WAY_TAG = 3702;
    public static final int ERROR_CODE_TRIVIAL_FIX = 3710;
    public static final int ERROR_CODE_SORTING = 3711;
    public static final int ERROR_CODE_PARTIAL_SORTING = 3712;
    public static final int ERROR_CODE_ROAD_TYPE = 3721;
    public static final int ERROR_CODE_CONSTRUCTION = 3722;
    public static final int ERROR_CODE_DIRECTION = 3731;
    public static final int ERROR_CODE_END_STOP = 3741;
    public static final int ERROR_CODE_SPLIT_WAY = 3742;
    public static final int ERROR_CODE_RELATION_MEMBER_ROLES = 3743;
    public static final int ERROR_CODE_SOLITARY_STOP_POSITION = 3751;
    public static final int ERROR_CODE_PLATFORM_PART_OF_HIGHWAY = 3752;
    public static final int ERROR_CODE_STOP_NOT_SERVED = 3753;
    public static final int ERROR_CODE_STOP_BY_STOP = 3754;
    public static final int ERROR_CODE_NOT_PART_OF_STOP_AREA = 3761;
    public static final int ERROR_CODE_STOP_AREA_NO_STOPS = 3762;
    public static final int ERROR_CODE_STOP_AREA_NO_PLATFORM = 3763;
    public static final int ERROR_CODE_STOP_AREA_COMPARE_RELATIONS = 3764;
    public static final int ERROR_CODE_ROUTE_REF = 3765;

    public PTAssistantValidatorTest() {
        super(tr("Public Transport Assistant tests"),
                tr("Check if route relations are compatible with public transport version 2"));
    }

    @Override
    public void visit(Node n) {
        if (n.isIncomplete()) {
            return;
        }

        NodeChecker nodeChecker = new NodeChecker(n, this);

        // select only stop_positions
        if (StopUtils.isStopPosition(n)) {

            // check if stop positions are on a way:
            nodeChecker.performSolitaryStopPositionTest();

            if (PTProperties.STOP_AREA_TESTS.get()) {
                // check if stop positions are in any stop_area relation:
                nodeChecker.performNodePartOfStopAreaTest();
            }
            nodeChecker.performRouteRefMatchingTest(n);
        }

        // select only platforms
        if (n.hasTag("public_transport", "platform")) {

            // check that platforms are not part of any way:
            nodeChecker.performPlatformPartOfWayTest();

            if (PTProperties.STOP_AREA_TESTS.get()) {
                // check if platforms are in any stop_area relation:
                nodeChecker.performNodePartOfStopAreaTest();
            }
            nodeChecker.performRouteRefMatchingTest(n);
        }

        this.errors.addAll(nodeChecker.getErrors());

    }

    @Override
    public void visit(Relation r) {
        // Download incomplete members. If the download does not work, return
        // and do not do any testing.
        if (r.hasIncompleteMembers()) {

            boolean downloadSuccessful = this.downloadIncompleteMembers();
            if (!downloadSuccessful) {
                return;
            }
        }

        if (r.hasIncompleteMembers()) {
            return;
        }

        // Do some testing on stop area relations
        if (PTProperties.STOP_AREA_TESTS.get() && StopUtils.isStopArea(r)) {

            StopChecker stopChecker = new StopChecker(r, this);

            // Check if stop area relation has one stop position.
            stopChecker.performStopAreaStopPositionTest();

            // Check if stop area relation has one platform.
            stopChecker.performStopAreaPlatformTest();

            // Check if stop position(s) belong the same route relation as
            // related platform(s)
            stopChecker.performStopAreaRelationsTest();

            // Attach thrown errors
            this.errors.addAll(stopChecker.getErrors());
        }

        if (!RouteUtils.isVersionTwoPTRoute(r)) {
            return;
        }

        // Check individual ways using the oneway direction test and the road
        // type test:
        WayChecker wayChecker = new WayChecker(r, this);
        wayChecker.performDirectionTest();
        wayChecker.performRoadTypeTest();
        this.errors.addAll(wayChecker.getErrors());

        proceedWithSorting(r);

        // This allows to modify the route before the sorting and
        // SegmentChecker are carried out:
        // if (this.errors.isEmpty()) {
        // proceedWithSorting(r);
        // } else {
        // this.proceedAfterWayCheckerErrors(r);
        // }

    }

    /**
     * Downloads incomplete relation members in an extra thread (user input
     * required)
     *
     * @return true if successful, false if not successful
     */
    private boolean downloadIncompleteMembers() {

        final int[] userSelection = {0};

        try {

            if (SwingUtilities.isEventDispatchThread()) {

                userSelection[0] = showIncompleteMembersDownloadDialog();

            } else {

                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            userSelection[0] = showIncompleteMembersDownloadDialog();
                        } catch (InterruptedException e) {
                            Logging.error(e);
                        }

                    }
                });

            }

        } catch (InterruptedException | InvocationTargetException e) {
            return false;
        }

        if (userSelection[0] == JOptionPane.YES_OPTION) {

            Thread t = new IncompleteMembersDownloadThread();
            t.start();
            synchronized (t) {
                try {
                    t.wait();
                } catch (InterruptedException e) {
                    return false;
                }
            }

        }

        return true;

    }

    /**
     * Shows the dialog asking the user about an incomplete member download
     *
     * @return user's selection
     * @throws InterruptedException
     *             if interrupted
     */
    private int showIncompleteMembersDownloadDialog() throws InterruptedException {
        return PTProperties.DOWNLOAD_INCOMPLETE.get() ? JOptionPane.YES_OPTION : JOptionPane.NO_OPTION;

        // TODO: The following is dead code! Either revive it or throw it away.
        // IncompleteMembersDownloadDialog incompleteMembersDownloadDialog = new IncompleteMembersDownloadDialog();
        // return incompleteMembersDownloadDialog.getUserSelection();
    }

    /**
     * Gets user input after errors were detected by WayChecker. Although this
     * method is not used in the current implementation, it can be used to fix
     * errors from the previous testing stage and modify the route before the
     * second stage of testing is carried out.
     * @param r relation
     */
    @SuppressWarnings("unused")
    private void proceedAfterWayCheckerErrors(Relation r) {

        // count errors of each type:
        int numberOfDirectionErrors = 0;
        int numberOfRoadTypeErrors = 0;
        for (TestError e : this.errors) {
            if (e.getCode() == ERROR_CODE_DIRECTION) {
                numberOfDirectionErrors++;
            }
            if (e.getCode() == ERROR_CODE_ROAD_TYPE) {
                numberOfRoadTypeErrors++;
            }
        }

        final int[] userInput = {0};
        final long idParameter = r.getId();
        final int directionErrorParameter = numberOfDirectionErrors;
        final int roadTypeErrorParameter = numberOfRoadTypeErrors;

        if (SwingUtilities.isEventDispatchThread()) {

            userInput[0] = showProceedDialog(idParameter, directionErrorParameter, roadTypeErrorParameter);

        } else {

            try {
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        userInput[0] = showProceedDialog(idParameter, directionErrorParameter, roadTypeErrorParameter);

                    }
                });
            } catch (InvocationTargetException | InterruptedException e1) {
                Logging.error(e1);
            }

        }

        if (userInput[0] == 0) {
            this.fixErrorFromPlugin(this.errors);
            proceedWithSorting(r);
            return;
        }

        if (userInput[0] == 1) {
            JOptionPane.showMessageDialog(null, "This is not implemented yet!");
            return;
        }

        if (userInput[0] == 2) {
            proceedWithSorting(r);
        }

        // if userInput==-1 (i.e. no input), do nothing and stop testing of the
        // route.

    }

    private int showProceedDialog(long id, int numberOfDirectionErrors, int numberOfRoadTypeErrors) {

        if (numberOfDirectionErrors == 0 && numberOfRoadTypeErrors == 0) {
            return 2;
        }

        return PTProperties.PROCEED_WITHOUT_FIX.get() ? 2 : 0;

        // TODO: The following is dead code! Either revive it or throw it away.
        // ProceedDialog proceedDialog = new ProceedDialog(id, numberOfDirectionErrors, numberOfRoadTypeErrors);
        // return proceedDialog.getUserSelection();

    }

    /**
     * Carries out the second stage of the testing: sorting and segments
     *
     * @param r
     *            relation
     */
    private void proceedWithSorting(Relation r) {

        PTRouteDataManager manager = new PTRouteDataManager(r);
        StopToWayAssigner assigner = new StopToWayAssigner(manager.getPTWays());

        for (RelationMember rm : manager.getFailedMembers()) {
            List<Relation> primitives = new ArrayList<>(1);
            primitives.add(r);
            List<OsmPrimitive> highlighted = new ArrayList<>(1);
            highlighted.add(rm.getMember());
            TestError.Builder builder = TestError.builder(this, Severity.WARNING,
                    ERROR_CODE_RELATION_MEMBER_ROLES);
            builder.message(tr("PT: Relation member roles do not match tags"));
            builder.primitives(primitives);
            builder.highlight(highlighted);
            TestError e = builder.build();
            errors.add(e);
        }

        // Check if the relation is correct, or only has a wrong sorting order:
        RouteChecker routeChecker = new RouteChecker(r, this);
        routeChecker.setManager(manager);
        routeChecker.setAssigner(assigner);
        if (!routeChecker.performFromToTagsTest()) {
            routeChecker.performFirstLastWayStopTest();
        }
        routeChecker.performSortingTest();

        List<TestError> routeCheckerErrors = routeChecker.getErrors();

        SegmentChecker segmentChecker = new SegmentChecker(r, this);
        segmentChecker.setManager(manager);
        segmentChecker.setAssigner(assigner);
        segmentChecker.performFirstStopTest();
        segmentChecker.performLastStopTest();
        segmentChecker.performStopNotServedTest();

        //At this point, there are 3 variants:
        if (routeCheckerErrors.isEmpty()) {
             if (!routeChecker.getHasGap()) {
                 //There are no errors => route is correct
                 storeCorrectRouteSegments(r, segmentChecker.getManager(),
                         segmentChecker.getAssigner());
             } else {
                 // There are some other errors/gaps that cannot be fixed by
                 // sorting => start further test (stop-by-stop)
                 segmentChecker.performStopByStopTest();
                 segmentChecker.findFixes();
             }
        } else {
            // There is only a sorting error (can only be 1), but otherwise
            // correct
            this.errors.addAll(routeChecker.getErrors());
        }

        //add eventual errors found
        if (!segmentChecker.getErrors().isEmpty()) {
            this.errors.addAll(segmentChecker.getErrors());
        }
    }

    @Override
    public void startTest(ProgressMonitor progressMonitor) {
        super.startTest(progressMonitor);

        // reset the static collections in SegmentChecker:
        SegmentChecker.reset();
    }

    /**
     * Method is called after all primitives has been visited, overrides the
     * method of the superclass.
     */
    @Override
    public void endTest() {

        // modify the error messages for the stop-by-stop test:
        SegmentChecker.modifyStopByStopErrorMessages();

        // add the stop-by-stop errors with modified messages:
        for (Entry<TestError.Builder, PTRouteSegment> entry : SegmentChecker.wrongSegmentBuilders.entrySet()) {
            TestError error = entry.getKey().build();
            SegmentChecker.wrongSegments.put(error, entry.getValue());
            this.errors.add(error);
        }

        super.endTest();

    }

    /**
     * Creates the PTRouteSegments of a route that has been found correct and
     * stores them in the list of correct route segments
     *
     * @param r
     *            route relation
     * @param manager route data manager
     * @param assigner stop to way assigner
     */
    public void storeCorrectRouteSegments(Relation r,
            PTRouteDataManager manager, StopToWayAssigner assigner) {
        if (manager.getPTStops().size() > 1) {
            for (int i = 1; i < manager.getPTStops().size(); i++) {
                PTStop startStop = manager.getPTStops().get(i - 1);
                PTStop endStop = manager.getPTStops().get(i);
                Way startWay = assigner.get(startStop);
                Way endWay = assigner.get(endStop);
                //if no startway and endway for this segment are found, don't store it
                if (startWay == null || endWay == null)
                    continue;
                List<PTWay> waysBetweenStops = manager.getPTWaysBetween(startWay, endWay);
                SegmentChecker.addCorrectSegment(
                        new PTRouteSegment(startStop, endStop, waysBetweenStops, r));
            }
        }
    }

    /**
     * Checks if the test error is fixable
     */
    @Override
    public boolean isFixable(TestError testError) {
        if (testError.getCode() == ERROR_CODE_DIRECTION
                || testError.getCode() == ERROR_CODE_ROAD_TYPE
                || testError.getCode() == ERROR_CODE_CONSTRUCTION
                || testError.getCode() == ERROR_CODE_SORTING
                || testError.getCode() == ERROR_CODE_PARTIAL_SORTING
                || testError.getCode() == ERROR_CODE_TRIVIAL_FIX
                || testError.getCode() == ERROR_CODE_END_STOP
                || testError.getCode() == ERROR_CODE_PLATFORM_PART_OF_HIGHWAY
                || testError.getCode() == ERROR_CODE_FIRST_LAST_STOP_WAY_TAG) {
            return true;
        }

        if (testError.getCode() == ERROR_CODE_STOP_BY_STOP && SegmentChecker.isFixable(testError)) {
            return true;
        }

        return false;
    }

    /**
     * Fixes the given error
     */
    @Override
    public Command fixError(TestError testError) {

        // repaint the relation in the pt_assistant layer:
        if (testError.getPrimitives().iterator().next().getType().equals(OsmPrimitiveType.RELATION)) {
            Relation relationToBeFixed = (Relation) testError.getPrimitives().iterator().next();
            PTAssistantLayerManager.PTLM.getLayer().repaint(relationToBeFixed);
        }

        // reset the last fix:
        PTAssistantPlugin.setLastFix(null);

        List<Command> commands = new ArrayList<>();

        if (testError.getCode() == ERROR_CODE_ROAD_TYPE
                || testError.getCode() == ERROR_CODE_CONSTRUCTION
                || testError.getCode() == ERROR_CODE_DIRECTION
                || testError.getCode() == ERROR_CODE_END_STOP) {
            commands.add(WayChecker.fixErrorByZooming(testError));
        }

        if (testError.getCode() == ERROR_CODE_TRIVIAL_FIX) {
            commands.add(RouteChecker.fixTrivialError(testError));
        }

        if (testError.getCode() == ERROR_CODE_SORTING
                || testError.getCode() == ERROR_CODE_PARTIAL_SORTING) {
            commands.add(RouteChecker.fixSortingError(testError));
        }

        if (testError.getCode() == ERROR_CODE_FIRST_LAST_STOP_WAY_TAG) {
            RouteChecker.fixFirstLastWayError(testError);
        }

        if (testError.getCode() == ERROR_CODE_SOLITARY_STOP_POSITION
                || testError.getCode() == ERROR_CODE_PLATFORM_PART_OF_HIGHWAY) {
            commands.add(NodeChecker.fixError(testError));
        }

        if (testError.getCode() == ERROR_CODE_STOP_BY_STOP) {
            commands.add(SegmentChecker.fixError(testError));
            // make sure the primitives of this testError are selected:
            Collection<OsmPrimitive> primitivesToSelect = new ArrayList<>();
            for (Object obj : testError.getPrimitives()) {
                primitivesToSelect.add((OsmPrimitive) obj);
            }
            SelectCommand selectCommand = new SelectCommand(OsmDataManager.getInstance().getEditDataSet(), primitivesToSelect);
            SwingUtilities.invokeLater(selectCommand::executeCommand);
        }

        if (commands.isEmpty()) {
            return null;
        }

        if (commands.size() == 1) {
            return commands.get(0);
        }

        return new SequenceCommand(tr("Fix error"), commands);
    }

    /**
     * This method is the counterpart of the fixError(TestError testError)
     * method. The fixError method is invoked from the core validator (e.g. when
     * user presses the "Fix" button in the validator). This method is invoken
     * when the fix is initiated from within the plugin (e.g. automated fixes).
     * @param testErrors list of errors
     */
    private void fixErrorFromPlugin(List<TestError> testErrors) {

        // run fix task asynchronously
        FixTask fixTask = new FixTask(testErrors);

        Thread t = new Thread(fixTask);
        t.start();
        try {
            t.join();
            errors.removeAll(testErrors);

        } catch (InterruptedException e) {
            JOptionPane.showMessageDialog(null, "Error occurred during fixing");
        }

    }

    public void addFixVariants(List<List<PTWay>> fixVariants) {
        PTAssistantLayerManager.PTLM.getLayer().addFixVariants(fixVariants);
    }

    public void clearFixVariants() {
        PTAssistantLayerManager.PTLM.getLayer().clearFixVariants();
    }

    public List<PTWay> getFixVariant(Character c) {
        return PTAssistantLayerManager.PTLM.getLayer().getFixVariant(c);
    }

    @SuppressWarnings("unused")
    private void performDummyTest(Relation r) {
        List<Relation> primitives = new ArrayList<>(1);
        primitives.add(r);
        TestError.Builder builder = TestError.builder(this, Severity.WARNING, ERROR_CODE_DIRECTION);
        builder.message(tr("PT: dummy test warning"));
        builder.primitives(primitives);
        errors.add(builder.build());
    }

}
