/*
 * *************************************************************************************
 *  Copyright (C) 2006-2015 EsperTech, Inc. All rights reserved.                       *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.regression.epl;

import com.espertech.esper.client.EPOnDemandQueryResult;
import com.espertech.esper.client.EPServiceProvider;
import com.espertech.esper.client.EPServiceProviderManager;
import com.espertech.esper.client.EPStatement;
import com.espertech.esper.client.scopetest.EPAssertionUtil;
import com.espertech.esper.client.scopetest.SupportSubscriber;
import com.espertech.esper.client.scopetest.SupportUpdateListener;
import com.espertech.esper.client.soda.EPStatementObjectModel;
import com.espertech.esper.client.soda.SelectClause;
import com.espertech.esper.client.soda.FromClause;
import com.espertech.esper.client.soda.FilterStream;
import com.espertech.esper.client.time.CurrentTimeEvent;
import com.espertech.esper.metrics.instrumentation.InstrumentationHelper;
import com.espertech.esper.support.bean.SupportBean;
import com.espertech.esper.support.bean.SupportBean_A;
import com.espertech.esper.support.bean.SupportBean_N;
import com.espertech.esper.support.client.SupportConfigFactory;
import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Map;

public class TestDistinct extends TestCase
{
    private EPServiceProvider epService;
    private SupportUpdateListener listener;

    public void setUp()
    {
        listener = new SupportUpdateListener();
        epService = EPServiceProviderManager.getDefaultProvider(SupportConfigFactory.getConfiguration());
        epService.initialize();
        if (InstrumentationHelper.ENABLED) { InstrumentationHelper.startTest(epService, this.getClass(), getName());}
        epService.getEPAdministrator().getConfiguration().addEventType("SupportBean", SupportBean.class);
        epService.getEPAdministrator().getConfiguration().addEventType("SupportBean_A", SupportBean_A.class);
        epService.getEPAdministrator().getConfiguration().addEventType("SupportBean_N", SupportBean_N.class);
    }

    protected void tearDown() throws Exception {
        if (InstrumentationHelper.ENABLED) { InstrumentationHelper.endTest();}
        listener = null;
    }

    public void testWildcardJoinPattern() {
        String epl = "select distinct * from " +
                "SupportBean(intPrimitive=0) as fooB unidirectional " +
                "inner join " +
                "pattern [" +
                "every-distinct(fooA.theString) fooA=SupportBean(intPrimitive=1)" +
                "->" +
                "every-distinct(wooA.theString) wooA=SupportBean(intPrimitive=2)" +
                " where timer:within(1 hour)" +
                "].win:time(1 hour) as fooWooPair " +
                "on fooB.longPrimitive = fooWooPair.fooA.longPrimitive";

        SupportSubscriber subs = new SupportSubscriber();
        EPStatement stmt = epService.getEPAdministrator().createEPL(epl);
        stmt.addListener(listener);

        sendEvent("E1", 1, 10L);
        sendEvent("E1", 2, 10L);

        sendEvent("E2", 1, 10L);
        sendEvent("E2", 2, 10L);

        sendEvent("E3", 1, 10L);
        sendEvent("E3", 2, 10L);

        sendEvent("Query", 0, 10L);
        assertTrue(listener.isInvoked());
    }

    private void sendEvent(String theString, int intPrimitive, long longPrimitive) {
        SupportBean bean = new SupportBean(theString, intPrimitive);
        bean.setLongPrimitive(longPrimitive);
        epService.getEPRuntime().sendEvent(bean);
    }

    public void testOnDemandAndOnSelect()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        epService.getEPAdministrator().createEPL("create window MyWindow.win:keepall() as select * from SupportBean");
        epService.getEPAdministrator().createEPL("insert into MyWindow select * from SupportBean");

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        
        String query = "select distinct theString, intPrimitive from MyWindow order by theString, intPrimitive";
        EPOnDemandQueryResult result = epService.getEPRuntime().executeQuery(query);
        EPAssertionUtil.assertPropsPerRow(result.getArray(), fields, new Object[][]{{"E1", 1}, {"E1", 2}, {"E2", 2}});

        EPStatement stmt = epService.getEPAdministrator().createEPL("on SupportBean_A select distinct theString, intPrimitive from MyWindow order by theString, intPrimitive asc");
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_A("x"));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E1", 1}, {"E1", 2}, {"E2", 2}});
    }

    public void testSubquery()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        EPStatement stmt = epService.getEPAdministrator().createEPL("select * from SupportBean where theString in (select distinct id from SupportBean_A.win:keepall())");
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 2));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E1", 2});

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 3));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E1", 3});
    }

    // Since the "this" property will always be unique, this test verifies that condition
    public void testBeanEventWildcardThisProperty()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        String statementText = "select distinct * from SupportBean.win:keepall()";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 2}, {"E1", 1}});
    }

    public void testBeanEventWildcardSODA()
    {
        String[] fields = new String[] {"id"};
        String statementText = "select distinct * from SupportBean_A.win:keepall()";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1"}});

        epService.getEPRuntime().sendEvent(new SupportBean_A("E2"));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1"}, {"E2"}});

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1"}, {"E2"}});
        
        EPStatementObjectModel model = epService.getEPAdministrator().compileEPL(statementText);
        assertEquals(statementText, model.toEPL());

        model = new EPStatementObjectModel();
        model.setSelectClause(SelectClause.createWildcard().distinct(true));
        model.setFromClause(FromClause.create(FilterStream.create("SupportBean_A")));
        assertEquals("select distinct * from SupportBean_A", model.toEPL());
    }

    public void testBeanEventWildcardPlusCols()
    {
        String[] fields = new String[] {"intPrimitive", "val1", "val2"};
        String statementText = "select distinct *, intBoxed%5 as val1, intBoxed as val2 from SupportBean_N.win:keepall()";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_N(1, 8));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{1, 3, 8}});

        epService.getEPRuntime().sendEvent(new SupportBean_N(1, 3));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{1, 3, 8}, {1, 3, 3}});

        epService.getEPRuntime().sendEvent(new SupportBean_N(1, 8));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{1, 3, 8}, {1, 3, 3}});
    }

    public void testMapEventWildcard()
    {
        Map<String, Object> def = new HashMap<String, Object>();
        def.put("k1", String.class);
        def.put("v1", int.class);
        epService.getEPAdministrator().getConfiguration().addEventType("MyMapType", def);

        String[] fields = new String[] {"k1", "v1"};
        String statementText = "select distinct * from MyMapType.win:keepall()";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        sendMapEvent("E1", 1);
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}});

        sendMapEvent("E2", 2);
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});

        sendMapEvent("E1", 1);
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});
    }

    public void testOutputSimpleColumn()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        String statementText = "select distinct theString, intPrimitive from SupportBean.win:keepall()";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        runAssertionSimpleColumn(stmt, fields);
        stmt.destroy();
        
        // test join
        statementText = "select distinct theString, intPrimitive from SupportBean.win:keepall() a, SupportBean_A.win:keepall() b where a.theString = b.id";
        stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        epService.getEPRuntime().sendEvent(new SupportBean_A("E2"));
        runAssertionSimpleColumn(stmt, fields);
    }

    public void testOutputLimitEveryColumn()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        String statementText = "@IterableUnbound select distinct theString, intPrimitive from SupportBean output every 3 events";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        runAssertionOutputEvery(stmt, fields);
        stmt.destroy();

        // test join
        statementText = "select distinct theString, intPrimitive from SupportBean.std:lastevent() a, SupportBean_A.win:keepall() b where a.theString = b.id output every 3 events";
        stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        epService.getEPRuntime().sendEvent(new SupportBean_A("E2"));
        runAssertionOutputEvery(stmt, fields);
    }

    public void testOutputRateSnapshotColumn()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        String statementText = "select distinct theString, intPrimitive from SupportBean.win:keepall() output snapshot every 3 events order by theString asc";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        runAssertionSnapshotColumn(stmt, fields);
        stmt.destroy();
        
        statementText = "select distinct theString, intPrimitive from SupportBean.win:keepall() a, SupportBean_A.win:keepall() b where a.theString = b.id output snapshot every 3 events order by theString asc";
        stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        epService.getEPRuntime().sendEvent(new SupportBean_A("E2"));
        epService.getEPRuntime().sendEvent(new SupportBean_A("E3"));
        runAssertionSnapshotColumn(stmt, fields);
    }

    public void testBatchWindow()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        String statementText = "select distinct theString, intPrimitive from SupportBean.win:length_batch(3)";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}});
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRow(listener.getAndResetLastNewData(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRow(listener.getAndResetLastNewData(), fields, new Object[][]{{"E2", 2}, {"E1", 1}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        EPAssertionUtil.assertPropsPerRow(listener.getAndResetLastNewData(), fields, new Object[][]{{"E2", 3}});

        stmt.destroy();

        // test batch window with aggregation
        epService.getEPRuntime().sendEvent(new CurrentTimeEvent(0));
        String[] fieldsTwo = new String[] {"c1", "c2"};
        String epl = "insert into ABC select distinct theString as c1, first(intPrimitive) as c2 from SupportBean.win:time_batch(1 second)";
        EPStatement stmtTwo = epService.getEPAdministrator().createEPL(epl);
        stmtTwo.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));

        epService.getEPRuntime().sendEvent(new CurrentTimeEvent(1000));
        EPAssertionUtil.assertPropsPerRow(listener.getAndResetLastNewData(), fieldsTwo, new Object[][]{{"E1", 1}, {"E2", 1}});

        epService.getEPRuntime().sendEvent(new CurrentTimeEvent(2000));
        assertFalse(listener.isInvoked());
    }

    public void testBatchWindowJoin()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        String statementText = "select distinct theString, intPrimitive from SupportBean.win:length_batch(3) a, SupportBean_A.win:keepall() b where a.theString = b.id";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean_A("E1"));
        epService.getEPRuntime().sendEvent(new SupportBean_A("E2"));

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E2", 2}, {"E1", 1}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E2", 3}});
    }

    public void testBatchWindowInsertInto()
    {
        String[] fields = new String[] {"theString", "intPrimitive"};
        String statementText = "insert into MyStream select distinct theString, intPrimitive from SupportBean.win:length_batch(3)";
        epService.getEPAdministrator().createEPL(statementText);

        statementText = "select * from MyStream";
        EPStatement stmt = epService.getEPAdministrator().createEPL(statementText);
        stmt.addListener(listener);

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E1", 1});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E3", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertProps(listener.getNewDataListFlattened()[0], fields, new Object[]{"E2", 2});
        EPAssertionUtil.assertProps(listener.getNewDataListFlattened()[1], fields, new Object[]{"E3", 3});
    }

    private void runAssertionOutputEvery(EPStatement stmt, String[] fields)
    {
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}});
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});
        listener.reset();

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E2", 2}, {"E1", 1}});
        listener.reset();

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 3));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E2", 3}});
        listener.reset();
    }

    private void runAssertionSimpleColumn(EPStatement stmt, String[] fields)
    {
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}});
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E1", 1});

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}});
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E1", 1});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 1}});
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E2", 1});

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 2));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 1}, {"E1", 2}});
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E1", 2});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 1}, {"E1", 2}, {"E2", 2}});
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E2", 2});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 1}, {"E1", 2}, {"E2", 2}});
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E2", 2});

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 1}, {"E1", 2}, {"E2", 2}});
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"E1", 1});
    }

    private void runAssertionSnapshotColumn(EPStatement stmt, String[] fields)
    {
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}});
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E1", 1}, {"E2", 2}});
        listener.reset();

        epService.getEPRuntime().sendEvent(new SupportBean("E3", 3));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 2));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), fields, new Object[][]{{"E1", 1}, {"E2", 2}, {"E3", 3}});
        EPAssertionUtil.assertPropsPerRow(listener.getLastNewData(), fields, new Object[][]{{"E1", 1}, {"E2", 2}, {"E3", 3}});
        listener.reset();
    }

    private void sendMapEvent(String s, int i)
    {
        Map<String, Object> def = new HashMap<String, Object>();
        def.put("k1", s);
        def.put("v1", i);
        epService.getEPRuntime().sendEvent(def, "MyMapType");
    }
}