package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openpnp.model.BoardLocation;

/**
 * Board -> pads -> volume (A * t * k) -> rotation via calibration q -> dots -> order (nearest
 * neighbour + 2-opt). Deterministic: no fitted model, every dot cites the calibration it used.
 */
public class DispensePlanner {
    private final DispenseConfig cfg;
    private final PadTypeResolver resolver;
    private final Calibration calibration;
    private final DotLadder ladder;
    private java.util.Map<String, PadRule> rules = java.util.Collections.emptyMap();

    public DispensePlanner(DispenseConfig cfg, PadTypeResolver resolver, Calibration calibration,
            List<DotLadderRow> ladderRows) {
        if (calibration == null) {
            throw new IllegalArgumentException("no calibration record");
        }
        this.cfg = cfg;
        this.resolver = resolver;
        this.calibration = calibration;
        this.ladder = DotLadder.of(ladderRows);
    }

    /** Per pad-type layout rules (pad_rules); missing entries use the config defaults. */
    public DispensePlanner withRules(java.util.Map<String, PadRule> rules) {
        this.rules = rules == null ? java.util.Collections.emptyMap() : rules;
        return this;
    }

    public DotLadder getLadder() {
        return ladder;
    }

    /** Plans an OpenPnP board location (pads from packages.xml footprints or pad types). */
    public DispensePlan planBoard(BoardLocation bl, double startX, double startY) {
        BoardPadExtractor.Result r = new BoardPadExtractor(resolver, cfg.includeDisabledPlacements).extract(bl);
        String name = bl.getBoard() == null ? bl.getId() : bl.getBoard().getName();
        DispensePlan plan = plan(name, r.pads, startX, startY);
        plan.boardLocationId = bl.getId();
        plan.warnings.addAll(0, r.warnings);
        return plan;
    }

    public DispensePlan plan(String boardName, List<PadGeometry> pads, double startX, double startY) {
        DispensePlan plan = new DispensePlan();
        plan.planId = UUID.randomUUID().toString();
        plan.boardName = boardName;
        plan.createdAtMs = System.currentTimeMillis();
        plan.calibrationId = calibration.id;
        plan.qMm3PerRev = calibration.qMm3PerRev;
        plan.calibrationStatus = calibration.status;
        plan.needleGapMm = cfg.needleGapMm;
        plan.needleZOffsetMm = cfg.needleZOffsetMm;
        plan.hopHeightMm = cfg.hopHeightMm;
        double density = calibration.densityMgMm3 != null ? calibration.densityMgMm3 : cfg.pasteDensityMgMm3;
        DotSizer sizer = new DotSizer(calibration.qMm3PerRev, ladder, cfg.maxDotsPerPad, cfg.dotFitFactor, density);
        PadTypeStatus minStatus = cfg.minPadTypeStatus();

        List<double[]> dotXy = new ArrayList<>();
        List<DispensePlan.PlannedDot> unordered = new ArrayList<>();
        for (PadGeometry g : pads) {
            DispensePlan.PlannedPad p = new DispensePlan.PlannedPad();
            p.padId = g.padId;
            p.placementId = g.placementId;
            p.packageId = g.packageId;
            p.source = g.source;
            p.xMm = g.xMm;
            p.yMm = g.yMm;
            p.rotationDeg = g.rotationDeg;
            p.wMm = g.wMm;
            p.hMm = g.hMm;
            p.surfaceZMm = g.surfaceZMm;
            plan.pads.add(p);
            PadType t = g.padType;
            if (t == null) {
                p.flags.add(PadFlag.NO_PAD_TYPE);
                continue;
            }
            p.typeKey = t.typeKey;
            p.typeVersion = t.version;
            p.typeStatus = t.status;
            if (!t.status.atLeast(minStatus)) {
                p.flags.add(PadFlag.STATUS_BELOW_MIN);
            }
            if (g.wMm <= 0 || g.hMm <= 0) {
                p.flags.add(PadFlag.NO_GEOMETRY);
                continue;
            }
            if (!calibration.isCharacterized()) {
                p.flags.add(PadFlag.PROVISIONAL_CALIBRATION);
            }
            p.tMm = t.tMm;
            p.k = t.k;
            p.areaMm2 = DotMath.padAreaMm2(g.wMm, g.hMm, g.roundness);
            p.targetVolumeMm3 = DotMath.targetVolumeMm3(p.areaMm2, t.tMm, t.k);
            p.targetMassMg = p.targetVolumeMm3 * density;
            DotSizer.Result s = sizer.size(g.wMm, g.hMm, p.targetVolumeMm3, rules.get(t.typeKey));
            p.flags.addAll(s.flags);
            p.rotationPerDotDeg = s.rotationPerDotDeg;
            p.predictedDiameterMm = s.predictedDiameterMm;
            p.predictedHeightMm = s.predictedHeightMm;
            if (p.isBlocked()) {
                p.dotCount = 0;
                continue;
            }
            p.dotCount = s.dotCount();
            double rad = Math.toRadians(g.rotationDeg);
            double c = Math.cos(rad), sn = Math.sin(rad);
            for (double[] o : s.offsets) {
                DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
                d.padId = g.padId;
                d.xMm = g.xMm + o[0] * c - o[1] * sn;
                d.yMm = g.yMm + o[0] * sn + o[1] * c;
                d.surfaceZMm = g.surfaceZMm;
                d.dispenseZMm = g.surfaceZMm + cfg.needleZOffsetMm + cfg.needleGapMm;
                d.hopZMm = d.dispenseZMm + cfg.hopHeightMm;
                d.rotationDeg = s.rotationPerDotDeg;
                d.expectedDiameterMm = s.predictedDiameterMm;
                unordered.add(d);
                dotXy.add(new double[] {d.xMm, d.yMm});
            }
        }
        double[] xs = new double[unordered.size()];
        double[] ys = new double[unordered.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = dotXy.get(i)[0];
            ys[i] = dotXy.get(i)[1];
        }
        PathOrder.Result order = PathOrder.order(xs, ys, startX, startY, cfg.twoOptMaxPasses);
        for (int i = 0; i < order.order.length; i++) {
            DispensePlan.PlannedDot d = unordered.get(order.order[i]);
            d.index = i;
            plan.dots.add(d);
        }
        plan.pathLengthNearestNeighbourMm = order.nearestNeighbourLength;
        plan.pathLengthMm = order.length;
        return plan;
    }
}
