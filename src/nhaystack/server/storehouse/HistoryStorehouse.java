//
// Copyright (c) 2012, J2 Innovations
// Licensed under the Academic Free License version 3.0
//
// History:
//   04 Oct 2012  Mike Jarmy  Creation
//
package nhaystack.server.storehouse;

import java.util.*;

import javax.baja.control.*;
import javax.baja.history.*;
import javax.baja.history.ext.*;
import javax.baja.log.*;
import javax.baja.sys.*;
import javax.baja.util.*;

import haystack.*;
import nhaystack.*;
import nhaystack.server.*;

/**
  * HistoryStorehouse manages access to the BHistoryDatabase
  */
public class HistoryStorehouse extends Storehouse
{
    public HistoryStorehouse(NHServer server)
    {
        super(server);
    }

    /**
      * Create the haystack representation of a BHistoryConfig.
      *
      * The haystack representation is a combination of the 
      * autogenerated tags, and those tags specified
      * in the explicit haystack annotation (if any).
      *
      * This method never returns null.
      */
    public HDict createHistoryTags(BHistoryConfig cfg)
    {
        return doCreateHistoryTags(cfg, null);
    }

    /**
      * Return whether the given history
      * ought to be turned into a Haystack record.
      */
    public boolean isVisibleHistory(BHistoryConfig cfg)
    {
        return doIsVisibleHistory(cfg, null);
    }

    /**
      * Try to find either a local or imported history for the point
      */
    public BHistoryConfig lookupHistoryFromPoint(BControlPoint point)
    {
        // local history
        BHistoryExt historyExt = service.lookupHistoryExt(point);
        if (historyExt != null) return historyExt.getHistoryConfig();

        // look for history that goes with a proxied point (if any)
        if (point.getProxyExt().getType().is(RemotePoint.NIAGARA_PROXY_EXT)) 
            return lookupRemoteHistory(point);
        else 
            return null;
    }

    /**
      * Return navigation tree children for given navId. 
      */
    public HGrid onNav(String navId)
    {
        if (navId.equals(Sys.getStation().getStationName() + ":h"))
        {
            ConfigStorehouseIterator c = server.getConfigStorehouse().makeIterator();
            // force the iterator to collect all the remote points
            while (c.hasNext()) c.next();

            Iterator itr = makeIterator(c);

            Array dicts = new Array(HDict.class);
            while (itr.hasNext())
                dicts.add(itr.next());
            return HGridBuilder.dictsToGrid((HDict[]) dicts.trim());
        }
        else throw new BajaRuntimeException("Cannot lookup nav for " + navId);
    }

    /**
      * Iterator through all the histories.
      *
      * @param configIterator contains an internal data structure
      * that will make this Iterator run much faster.  It
      * is OK for configIterator to be null.  If configIterator is non-null,
      * then it must have been completely exhausted by
      * calls to next() before any calls are made to this Iterator,
      * or it will throw an IllegalStateException.
      */
    public HistoryStorehouseIterator makeIterator(
        ConfigStorehouseIterator configIterator)
    {
        return new HistoryStorehouseIterator(this, configIterator);
    }

////////////////////////////////////////////////////////////////
// package scope
////////////////////////////////////////////////////////////////

    /**
      * Create the haystack representation of a BHistoryConfig.
      *
      * The haystack representation is a combination of the 
      * autogenerated tags, and those tags specified
      * in the explicit haystack annotation (if any).
      *
      * This method never returns null.
      */
    HDict doCreateHistoryTags(
        BHistoryConfig cfg, 
        ConfigStorehouseIterator configIterator)
    {
        HDictBuilder hdb = new HDictBuilder();

        // add existing tags
        BHDict btags = BHDict.findTagAnnotation(cfg);
        HDict tags = (btags == null) ? HDict.EMPTY : btags.getDict();
        hdb.add(tags);

        // add id
        hdb.add("id", NHRef.make(cfg).getHRef());
        String dis = cfg.getDisplayName(null);

        // add misc other tags
        if (dis != null) hdb.add("dis", dis);
        hdb.add("axType", cfg.getType().toString());
        hdb.add("axHistoryId", cfg.getId().toString());

        hdb.add("point");
        hdb.add("his");

        // time zone
        if (!tags.has("tz"))
        {
            HTimeZone tz = makeTimeZone(cfg.getTimeZone());
            hdb.add("tz", tz.name);
        }

        // point kind tags
        Type recType = cfg.getRecordType().getResolvedType();
        if (recType.is(BTrendRecord.TYPE))
        {
            int pointKind = getTrendRecordKind(recType);
            BFacets facets = (BFacets) cfg.get("valueFacets");
            addPointKindTags(pointKind, facets, tags, hdb);
        }

        // check if this history has a point
        ConfigStorehouse cs = server.getConfigStorehouse();
        BControlPoint point = cs.lookupPointFromHistory(cfg, configIterator);
        if (point != null)
        {
            // add point ref
            hdb.add("axPointRef", NHRef.make(point).getHRef());

            // hisInterpolate 
            if (!tags.has("hisInterpolate"))
            {
                BHistoryExt historyExt = service.lookupHistoryExt(point);
                if (historyExt != null && (historyExt instanceof BCovHistoryExt))
                    hdb.add("hisInterpolate", "cov");
            }
        }

        // done
        return hdb.toDict();
    }

    /**
      * Return whether this history is visible to the outside world.
      */
    boolean doIsVisibleHistory(
        BHistoryConfig cfg, 
        ConfigStorehouseIterator configIterator)
    {
        // annotated 
        if (BHDict.findTagAnnotation(cfg) != null)
            return true;

        // show linked
        if (service.getShowLinkedHistories())
            return true;

        // make sure the history is not linked
        ConfigStorehouse cs = server.getConfigStorehouse();
        if (cs.lookupPointFromHistory(cfg, configIterator) == null)
            return true;

        return false;
    }

////////////////////////////////////////////////////////////////
// private
////////////////////////////////////////////////////////////////

    /**
      * Find the imported history that goes with an imported point, 
      * or return null.  
      */
    private BHistoryConfig lookupRemoteHistory(BControlPoint point)
    {
        RemotePoint remotePoint = RemotePoint.fromControlPoint(point);
        if (remotePoint == null) return null;

        synchronized(this)
        {
            ensureLoaded();
            return (BHistoryConfig) mapPointToConfig.get(remotePoint);
        }
    }

    /**
      * Stash away all the imported histories.
      * This only happens once.
      */
    private void ensureLoaded()
    {
        if (mapPointToConfig != null) return;

        mapPointToConfig = new HashMap();
        mapHistoryToPoint = new HashMap();

        BIHistory[] histories = service.getHistoryDb().getHistories(); 
        for (int i = 0; i < histories.length; i++)
        {
            BIHistory h = histories[i];
            BHistoryId hid = h.getId();
            BHistoryConfig cfg = h.getConfig();

            // ignore local histories
            if (hid.getDeviceName().equals(Sys.getStation().getStationName()))
                continue;

            RemotePoint remotePoint = RemotePoint.fromHistoryConfig(cfg);
            if (remotePoint != null)
            {
                mapPointToConfig.put(remotePoint, cfg);
                mapHistoryToPoint.put(hid, remotePoint);
            }
        }

        // now that we've loaded all the histories, we'll set up a
        // listener so that we can keep our HashMap up-to-date
        service.getHistoryDb().addHistoryEventListener(new Listener());
    }

    private static int getTrendRecordKind(Type trendRecType)
    {
        if      (trendRecType.is(BNumericTrendRecord.TYPE)) return NUMERIC_KIND;
        else if (trendRecType.is(BBooleanTrendRecord.TYPE)) return BOOLEAN_KIND;
        else if (trendRecType.is(BEnumTrendRecord.TYPE))    return ENUM_KIND;
        else if (trendRecType.is(BStringTrendRecord.TYPE))  return STRING_KIND;

        else return UNKNOWN_KIND;
    }

////////////////////////////////////////////////////////////////
// HistoryEventListener
////////////////////////////////////////////////////////////////

    private class Listener implements HistoryEventListener
    {
        public void historyEvent(BHistoryEvent event)
        {
            if (event.getId() == BHistoryEvent.CREATED)
                addHistory(event.getHistoryId());

            else if (event.getId() == BHistoryEvent.DELETED)
                removeHistory(event.getHistoryId());
        }

        private void addHistory(BHistoryId hid)
        {
            // ignore local histories
            if (hid.getDeviceName().equals(Sys.getStation().getStationName()))
                return;

            BIHistory h = service.getHistoryDb().getHistory(hid);
            BHistoryConfig cfg = h.getConfig();
            RemotePoint remotePoint = RemotePoint.fromHistoryConfig(cfg);

            synchronized(this)
            {
                if (remotePoint != null)
                {
                    mapPointToConfig.put(remotePoint, cfg);
                    mapHistoryToPoint.put(hid, remotePoint);
                }
            }
        }

        private void removeHistory(BHistoryId hid)
        {
            // ignore local histories
            if (hid.getDeviceName().equals(Sys.getStation().getStationName()))
                return;

            synchronized(this)
            {
                RemotePoint remotePoint = (RemotePoint) mapHistoryToPoint.get(hid);
                mapPointToConfig.remove(remotePoint);
                mapHistoryToPoint.remove(hid);
            }
        }
    }

////////////////////////////////////////////////////////////////
// Attributes 
////////////////////////////////////////////////////////////////

    private static final Log LOG = Log.getLog("nhaystack");

    // access to these data structures must be synchronized
    private Map mapPointToConfig  = null; // RemotePoint -> BHistoryConfig
    private Map mapHistoryToPoint = null; // BHistoryId -> RemotePoint
}

