/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * @version $Id: TableDiffCommand.java,v 1.10 2005-11-27 16:20:28 hzeller Exp $
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
package henplus.plugins.tablediff;

import henplus.AbstractCommand;
import henplus.CommandDispatcher;
import henplus.HenPlus;
import henplus.SQLSession;
import henplus.SessionManager;
import henplus.commands.ListUserObjectsCommand;
import henplus.logging.Logger;
import henplus.sqlmodel.Column;
import henplus.sqlmodel.Table;
import henplus.view.util.NameCompleter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;

public final class TableDiffCommand extends AbstractCommand {

    protected static final String COMMAND = "tablediff";
    protected static final String COMMAND_DELIMITER = ";";
    protected static final String OPTION_SINGLE_DB = "-singledb";

    /**
     * 
     */
    public TableDiffCommand() {
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#getCommandList()
     */
    @Override
    public String[] getCommandList() {
        return new String[] { COMMAND };
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#participateInCommandCompletion()
     */
    @Override
    public boolean participateInCommandCompletion() {
        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#execute(henplus.SQLSession, java.lang.String,
     * java.lang.String)
     */
    @Override
    public int execute(final SQLSession session, final String command, final String parameters) {
        // first set the option for case sensitive comparison of column names
        final boolean colNameIgnoreCase = true;
        final StringTokenizer st = new StringTokenizer(parameters);

        Logger.debug("[execute] command: '%s', parameters: '%s'", command, parameters);

        int result = SUCCESS;

        if (parameters.indexOf(OPTION_SINGLE_DB) != -1) {

            // required: session
            if (session == null) {
                Logger.error("You need a valid session for this command.");
                return EXEC_FAILED;
            }

            // required: option, table1, table2
            if (st.countTokens() != 3) {
                return SYNTAX_ERROR;
            }

            // push the tokenizer to skip the option
            st.nextToken();
            final String table1 = st.nextToken();
            final String table2 = st.nextToken();

            try {
                final long start = System.currentTimeMillis();

                diffTable(session, table1, table2, colNameIgnoreCase);

                final StringBuilder msg = new StringBuilder();
                msg.append("Diffing ").append(" tables ").append(table1).append(" and ").append(table2).append(" took ")
                        .append(System.currentTimeMillis() - start).append(" ms.");

                Logger.info(msg.toString());

            } catch (final Exception e) {
                e.printStackTrace();
            }

        } else {
            result = executeDoubleDb(st, colNameIgnoreCase);
        }

        return result;
    }

    private int executeDoubleDb(final StringTokenizer st, final boolean colNameIgnoreCase) {
        if (st.countTokens() < 3) {
            return SYNTAX_ERROR;
        }

        final SessionManager sessionManager = HenPlus.getInstance().getSessionManager();

        if (sessionManager.getSessionCount() < 2) {
            Logger.error("You need two valid sessions for this command.");
            return SYNTAX_ERROR;
        }

        final SQLSession first = sessionManager.getSessionByName(st.nextToken());
        final SQLSession second = sessionManager.getSessionByName(st.nextToken());

        if (first == null || second == null) {
            Logger.error("You need two valid sessions for this command.");
            return EXEC_FAILED;
        } else if (first == second) {
            Logger.error("You should specify two different sessions for this command.");
            return EXEC_FAILED;
        } else if (!st.hasMoreTokens()) {
            Logger.error("You should specify at least one table.");
            return EXEC_FAILED;
        }

        try {
            final long start = System.currentTimeMillis();
            int count = 0;

            final ListUserObjectsCommand objectLister = HenPlus.getInstance().getObjectLister();
            final SortedSet tablesOne = objectLister.getTableNamesForSession(first);
            final SortedSet tablesTwo = objectLister.getTableNamesForSession(second);

            final Set<String> alreadyDiffed = new HashSet<String>(); // which tables got already
            // diffed?

            /*
             * which tables are found in the first session via wildcards but are
             * not contained in the second session?
             */
            final ArrayList<String> missedFromWildcards = new ArrayList<String>();

            while (st.hasMoreTokens()) {

                final String nextToken = st.nextToken();

                if ("*".equals(nextToken) || nextToken.indexOf('*') > -1) {
                    Iterator iter = null;

                    if ("*".equals(nextToken)) {
                        iter = objectLister.getTableNamesIteratorForSession(first);
                    } else if (nextToken.indexOf('*') > -1) {
                        final String tablePrefix = nextToken.substring(0, nextToken.length() - 1);
                        final NameCompleter compl = new NameCompleter(tablesOne);
                        iter = compl.getAlternatives(tablePrefix);
                    }

                    while (iter.hasNext()) {
                        final Object objTableName = iter.next();
                        count = diffConditionally(objTableName, colNameIgnoreCase, first, second, tablesTwo, alreadyDiffed,
                                missedFromWildcards, count);
                    }
                } else if (!alreadyDiffed.contains(nextToken)) {
                    diffTable(first, second, nextToken, colNameIgnoreCase);
                    alreadyDiffed.add(nextToken);
                    count++;
                }

            }

            final StringBuilder msg = new StringBuilder();
            msg.append("Diffing ").append(count).append(count == 1 ? " table took " : " tables took ")
                    .append(System.currentTimeMillis() - start).append(" ms.");

            // if there were tables found via wildcards but not contained in
            // both sessions then let
            // the user know this.
            if (missedFromWildcards.size() > 0) {
                msg.append("\nTables which matched a given wildcard in your first\n"
                        + "session but were not found in your second session:\n");
                final Iterator iter = missedFromWildcards.iterator();
                while (iter.hasNext()) {
                    msg.append(iter.next()).append(", ");
                }
                // remove the last two chars
                msg.delete(msg.length() - 2, msg.length());
            }

            Logger.info(msg.toString());

        } catch (final Exception e) {
            e.printStackTrace();
        }

        return SUCCESS;
    }

    private int diffConditionally(final Object objTableName, final boolean colNameIgnoreCase, final SQLSession first,
            final SQLSession second, final SortedSet tablesTwo, final Set alreadyDiffed, final List missedFromWildcards, int count) {
        if (tablesTwo.contains(objTableName)) {
            if (!alreadyDiffed.contains(objTableName)) {
                final String tableName = (String) objTableName;
                diffTable(first, second, tableName, colNameIgnoreCase);
                alreadyDiffed.add(objTableName);
                count++;
            }
        } else {
            missedFromWildcards.add(objTableName);
        }
        return count;
    }

    private void diffTable(final SQLSession first, final SQLSession second, final String tableName, final boolean colNameIgnoreCase) {
        final Table ref = first.getTable(tableName);
        final Table diff = second.getTable(tableName);
        final TableDiffResult diffResult = TableDiffer.diffTables(ref, diff, colNameIgnoreCase);
        if (diffResult == null) {
            Logger.info("No diff for table " + tableName);
        } else {
            Logger.info("Diff result for table " + tableName + ":");
            ResultTablePrinter.printResult(diffResult);
        }
    }

    private void diffTable(final SQLSession session, final String tableName1, final String tableName2,
            final boolean colNameIgnoreCase) {
        final Table ref = session.getTable(tableName1);
        final Table diff = session.getTable(tableName2);
        final TableDiffResult diffResult = TableDiffer.diffTables(ref, diff, colNameIgnoreCase);
        if (diffResult == null) {
            Logger.info("No diff for tables " + tableName1 + " and " + tableName2 + ".");
        } else {
            Logger.info("Diff result for tables " + tableName1 + " and " + tableName2 + ":");
            ResultTablePrinter.printResult(diffResult);
        }
    }

    /* leave this for testing */
    private TableDiffResult getMockResult() {
        final TableDiffResult result = new TableDiffResult();

        final Column added = new Column("colname");
        added.setDefault("nix");
        added.setNullable(true);
        added.setPosition(23);
        added.setSize(666);
        added.setType("myType");
        result.addAddedColumn(added);

        final Column removed = new Column("wech");
        removed.setDefault("nix");
        removed.setNullable(true);
        removed.setPosition(23);
        removed.setSize(666);
        removed.setType("myType");
        result.addRemovedColumn(removed);

        final Column modOrg = new Column("orischinall");
        modOrg.setDefault("orgding");
        modOrg.setNullable(true);
        modOrg.setPosition(23);
        modOrg.setSize(666);
        modOrg.setType("myType");

        final Column modNew = new Column("moddifaied");
        modNew.setDefault("modding");
        modNew.setNullable(false);
        modNew.setPosition(42);
        modNew.setSize(999);
        modNew.setType("myType");

        result.putModifiedColumns(modOrg, modNew);

        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#complete(henplus.CommandDispatcher,
     * java.lang.String, java.lang.String)
     */
    @Override
    public Iterator complete(final CommandDispatcher disp, final String partialCommand, final String lastWord) {

        final StringTokenizer st = new StringTokenizer(partialCommand);
        st.nextToken(); // skip cmd.
        int argIndex = st.countTokens();

        /*
         * the following input is given: "command token1 [TAB_PRESSED]" in this
         * case the partialCommand is "command token1", the last word has a
         * length 0!
         * 
         * another input: "command toke[TAB_PRESSED]" then the partialCommand is
         * "command toke", the last word is "toke".
         */
        if (lastWord.length() > 0) {
            argIndex--;
        }

        // ========================= singledb =======================

        // check completion for --singledb
        if (argIndex == 0 && lastWord.startsWith("-")) {
            return new Iterator() {

                private boolean _next = true;

                @Override
                public boolean hasNext() {
                    return _next;
                }

                @Override
                public Object next() {
                    _next = false;
                    return OPTION_SINGLE_DB;
                }

                @Override
                public void remove() { /* do nothing */
                }
            };
        } else if (partialCommand.indexOf(OPTION_SINGLE_DB) != -1 && argIndex > 0) {

            final SessionManager sessionManager = HenPlus.getInstance().getSessionManager();
            final SQLSession session = sessionManager.getCurrentSession();

            final HashSet alreadyGiven = new HashSet();
            while (st.hasMoreElements()) {
                alreadyGiven.add(st.nextToken());
            }
            final ListUserObjectsCommand objectList = HenPlus.getInstance().getObjectLister();
            final Iterator iter = objectList.completeTableName(session, lastWord);
            return new Iterator() {

                String table = null;

                @Override
                public boolean hasNext() {
                    while (iter.hasNext()) {
                        table = (String) iter.next();
                        if (alreadyGiven.contains(table) && !lastWord.equals(table)) {
                            continue;
                        }
                        return true;
                    }
                    return false;
                }

                @Override
                public Object next() {
                    return table;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("no!");
                }
            };

        } else if (partialCommand.indexOf(OPTION_SINGLE_DB) == -1 && argIndex == 0) {
            // !singledb && process the first session
            return HenPlus.getInstance().getSessionManager().completeSessionName(lastWord);
        } else if (partialCommand.indexOf(OPTION_SINGLE_DB) == -1 && argIndex == 1) {
            // !singledb && process the second session
            final String firstSession = st.nextToken();
            return getSecondSessionCompleter(lastWord, firstSession);
        } else if (argIndex > 1) {
            // process tables
            final SessionManager sessionManager = HenPlus.getInstance().getSessionManager();
            final SQLSession first = sessionManager.getSessionByName(st.nextToken());
            final SQLSession second = sessionManager.getSessionByName(st.nextToken());

            final HashSet alreadyGiven = new HashSet();
            while (st.hasMoreElements()) {
                alreadyGiven.add(st.nextToken());
            }
            final ListUserObjectsCommand objectList = HenPlus.getInstance().getObjectLister();
            final Iterator firstIter = objectList.completeTableName(first, lastWord);
            final Iterator secondIter = objectList.completeTableName(second, lastWord);
            final Iterator iter = getIntersection(firstIter, secondIter);
            return new Iterator() {

                String table = null;

                @Override
                public boolean hasNext() {
                    while (iter.hasNext()) {
                        table = (String) iter.next();
                        if (alreadyGiven.contains(table) && !lastWord.equals(table)) {
                            continue;
                        }
                        return true;
                    }
                    return false;
                }

                @Override
                public Object next() {
                    return table;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("no!");
                }
            };
        }
        return null;
    }

    private Iterator<String> getIntersection(final Iterator<String> first, final Iterator<String> second) {
        // first copy the first iterator into a list
        final List<String> contentFirst = new ArrayList<String>();
        while (first.hasNext()) {
            contentFirst.add(first.next());
        }
        // now copy all items of the second iterator into a second list
        // which are contained in the first list
        final List<String> inter = new ArrayList<String>();
        while (second.hasNext()) {
            final String next = second.next();
            if (contentFirst.contains(next)) {
                inter.add(next);
            }
        }
        return inter.iterator();
    }

    private Iterator<String> getSecondSessionCompleter(final String lastWord, final String firstSession) {
        final Iterator<String> it = HenPlus.getInstance().getSessionManager().completeSessionName(lastWord);
        return new Iterator<String>() {

            String session = null;

            @Override
            public boolean hasNext() {
                while (it.hasNext()) {
                    session = it.next();
                    if (session.equals(firstSession)) {
                        continue;
                    }
                    return true;
                }
                return false;
            }

            @Override
            public String next() {
                return session;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("no!");
            }
        };
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#isComplete(java.lang.String)
     */
    @Override
    public boolean isComplete(final String command) {
        if (command.trim().endsWith(COMMAND_DELIMITER)) {
            return true;
            /*
             * StringTokenizer st = new StringTokenizer(command); // we need at
             * least four tokens. final int minTokens = 4; int count = 0; while
             * (st.hasMoreTokens() && count < minTokens) { count++; }
             */
        }
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#requiresValidSession(java.lang.String)
     */
    @Override
    public boolean requiresValidSession(final String cmd) {
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#shutdown()
     */
    @Override
    public void shutdown() {
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#getShortDescription()
     */
    @Override
    public String getShortDescription() {
        return "perform a diff on different tables";
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#getSynopsis(java.lang.String)
     */
    @Override
    public String getSynopsis(final String cmd) {
        return "\n" + COMMAND + " <sessionname-1> <sessionname-2> (<tablename> | <prefix>* | *)+;\n" + "or\n" + COMMAND + " "
                + OPTION_SINGLE_DB + " <table1> <table2>;\n";
    }

    /*
     * (non-Javadoc)
     * 
     * @see henplus.Command#getLongDescription(java.lang.String)
     */
    @Override
    public String getLongDescription(final String cmd) {
        return "\tCompare one or more tables by their meta data.\n" + "\n"
                + "\tThere are basically two use cases for comparing tables:\n"
                + "\t1. Compare tables with equal names from different databases and\n"
                + "\t2. Compare two tables with different names in the same database.\n" + "\n"
                + "\tFor the first use case you must specify two session names and one\n"
                + "\tor more tables that exist in both sessions.\n"
                + "\tYou are able to use wildcards (*) to match all tables or\n" + "\ta specific set of tables.\n"
                + "\tE.g. you might specify \"*\" to match all tables which are contained\n"
                + "\tin both sessions, or\"tb_*\" to match all tables from your sessions\n" + "\tstarting with \"tb_\".\n" + "\n"
                + "\tFor the second use case you must specifiy the option " + OPTION_SINGLE_DB + "\n" + "\tand two tables.\n"
                + "\n" + "\tThe following is a list of compared column related\n"
                + "\tproperties, with a \"c\" for a case sensitive and an \"i\" for\n"
                + "\ta case insensitive comparision by default. If you\n"
                + "\twonder what this is for, because you know that sql\n" + "\tshould behave case insensitive, then ask your\n"
                + "\tdatabase provider or the developer of the driver you use.\n" + "\n" + "\t - column name (i)\n"
                + "\t - type (c)\n" + "\t - nullable (-)\n" + "\t - default value (c)\n" + "\t - primary key definition (c)\n"
                + "\t - foreign key definition (c).\n" + "\n" + "\tIn the future indices migth be added to the comparison,\n"
                + "\tmoreover, an option \"o\" would be nice to get automatically\n"
                + "\t\"ALTER TABLE ...\" scripts generated to a given output file.";
    }
}
