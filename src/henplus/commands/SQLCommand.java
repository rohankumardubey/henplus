/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.AbstractCommand;
import henplus.CommandDispatcher;
import henplus.HenPlus;
import henplus.PropertyRegistry;
import henplus.SQLSession;
import henplus.SigIntHandler;
import henplus.logging.Logger;
import henplus.property.BooleanPropertyHolder;
import henplus.property.PropertyHolder;
import henplus.view.util.CancelWriter;
import henplus.view.util.NameCompleter;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * document me.
 */
public final class SQLCommand extends AbstractCommand {

    private static final String[] TABLE_COMPLETER_KEYWORD = { "FROM", "INTO", "UPDATE", "TABLE", "ALIAS", "VIEW", /* create index */
    "ON" };

    /**
     * returns the command-strings this command can handle.
     */
    @Override
    public String[] getCommandList() {
        return new String[] {
                // provide tab-completion at least for these command starts..
                "select", "insert", "update", "delete", "create", "alter", "drop", "commit", "rollback",
                /* "call-procedure", test */
                // we support _any_ string, that is not part of the
                // henplus buildin-stuff; the following empty string flags this.
                "" };
    }

    private final ListUserObjectsCommand _tableCompleter;
    private Statement _stmt;
    private String _columnDelimiter;
    private int _rowLimit;
    private boolean _showHeader;
    private boolean _showFooter;
    private volatile boolean _running;
    private StatementCanceller _statementCanceller;

    protected SQLCommand(final ListUserObjectsCommand tc) {
        _columnDelimiter = "|";
        _rowLimit = 2000;
        _tableCompleter = tc;
    }

    private LongRunningTimeDisplay _longRunningDisplay;

    public SQLCommand(final ListUserObjectsCommand tc, final PropertyRegistry registry) {
        _tableCompleter = tc;
        _columnDelimiter = "|";
        _rowLimit = 2000;
        _showHeader = true;
        _showFooter = true;
        registry.registerProperty("column-delimiter", new SQLColumnDelimiterProperty());
        registry.registerProperty("sql-result-limit", new RowLimitProperty());
        registry.registerProperty("sql-result-showheader", new ShowHeaderProperty());
        registry.registerProperty("sql-result-showfooter", new ShowFooterProperty());
        _statementCanceller = new StatementCanceller(new CurrentStatementCancelTarget());
        new Thread(_statementCanceller).start();
        _longRunningDisplay = new LongRunningTimeDisplay("statement running", 30000);
        new Thread(_longRunningDisplay).start();
    }

    /**
     * don't show the commands available in the toplevel command completion list ..
     */
    @Override
    public boolean participateInCommandCompletion() {
        return false;
    }

    /**
     * complicated SQL statements are only complete with semicolon. Simple commands may have no semicolon (like 'commit' and
     * 'rollback'). Yet others are not complete even if we encounter a semicolon (like triggers and stored procedures). We support
     * the SQL*PLUS syntax in that we consider these kind of statements complete with a single slash ('/') at the beginning of a
     * line.
     */
    @Override
    public boolean isComplete(String command) {
        command = command.toUpperCase(); // fixme: expensive.
        if (command.startsWith("COMMIT") || command.startsWith("ROLLBACK")) {
            return true;
        }
        // FIXME: this is a very dumb 'parser'.
        // i.e. string literals are not considered.
        final boolean anyProcedure = command.startsWith("BEGIN")
                || command.startsWith("DECLARE")
                || (command.startsWith("CREATE") || command.startsWith("REPLACE"))
                && (containsWord(command, "PROCEDURE") || containsWord(command, "FUNCTION") || containsWord(command, "PACKAGE") || containsWord(
                        command, "TRIGGER"));

        if (!anyProcedure && command.endsWith(";")) {
            return true;
        }
        // sqlplus is complete on a single '/' on a line.
        if (command.length() >= 3) {
            final int lastPos = command.length() - 1;
            if (command.charAt(lastPos) == '\n' && command.charAt(lastPos - 1) == '/' && command.charAt(lastPos - 2) == '\n') {
                return true;
            }
        }
        return false;
    }

    public void setColumnDelimiter(final String value) {
        _columnDelimiter = value;
    }

    public String getColumnDelimiter() {
        return _columnDelimiter;
    }

    public void setRowLimit(final int rowLimit) {
        _rowLimit = rowLimit;
    }

    public int getRowLimit() {
        return _rowLimit;
    }

    public void setShowHeader(final boolean b) {
        _showHeader = b;
    }

    public boolean isShowHeader() {
        return _showHeader;
    }

    public void setShowFooter(final boolean b) {
        _showFooter = b;
    }

    public boolean isShowFooter() {
        return _showFooter;
    }

    /**
     * A statement cancel target that accesses the instance wide statement.
     */
    private final class CurrentStatementCancelTarget implements StatementCanceller.CancelTarget {

        @Override
        public void cancelRunningStatement() {
            try {
                HenPlus.msg().println("cancel statement...");
                HenPlus.msg().flush();
                final CancelWriter info = new CancelWriter(HenPlus.msg());
                info.print("please wait");
                _stmt.cancel();
                info.cancel();
                HenPlus.msg().println("done.");
                _running = false;
            } catch (final Exception e) {
                Logger.debug("Exception while cancelling a statement: ", e);
            }
        }
    }

    /**
     * looks, if this word is contained in 'all', preceeded and followed by a whitespace.
     */
    private boolean containsWord(final String all, final String word) {
        final int wordLen = word.length();
        final int index = all.indexOf(word);
        return index >= 0 && (index == 0 || Character.isWhitespace(all.charAt(index - 1)))
                && Character.isWhitespace(all.charAt(index + wordLen));
    }

    /**
     * execute the command given.
     */
    @Override
    public int execute(final SQLSession session, final String cmd, final String param) {
        String command = cmd + " " + param;
        // boolean background = false;

        if (command.endsWith("/")) {
            command = command.substring(0, command.length() - 1);
        }

        // if (command.endsWith("&")) {
        // command = command.substring(0, command.length()-1);
        // HenPlus.msg().println(
        // "## executing command in the background not yet supported");
        // background = true;
        // }

        final long startTime = System.currentTimeMillis();
        long lapTime = -1;
        long execTime = -1;
        ResultSet rset = null;
        _running = true;
        SigIntHandler.getInstance().pushInterruptable(_statementCanceller);
        try {
            if (command.startsWith("commit")) {
                session.print("commit..");
                session.getConnection().commit();
                session.println(".done.");
            } else if (command.startsWith("rollback")) {
                session.print("rollback..");
                session.getConnection().rollback();
                session.println(".done.");
            } else {
                _stmt = session.createStatement();
                try {
                    _stmt.setFetchSize(200);
                } catch (final Exception e) {
                    /* ignore */
                }

                _statementCanceller.arm();
                _longRunningDisplay.arm();
                final boolean hasResultSet = _stmt.execute(command);
                _longRunningDisplay.disarm();

                if (!_running) {
                    HenPlus.msg().println("cancelled");
                    return SUCCESS;
                }

                if (hasResultSet) {
                    rset = _stmt.getResultSet();
                    ResultSetRenderer renderer;
                    renderer = new ResultSetRenderer(rset, getColumnDelimiter(), isShowHeader(), isShowFooter(), getRowLimit(),
                            HenPlus.out());
                    SigIntHandler.getInstance().pushInterruptable(renderer);
                    final int rows = renderer.execute();
                    SigIntHandler.getInstance().popInterruptable();
                    if (renderer.limitReached()) {
                        session.println("limit of " + getRowLimit() + " rows reached ..");
                        session.print("> ");
                    }
                    session.print(rows + " row" + (rows == 1 ? "" : "s") + " in result");
                    lapTime = renderer.getFirstRowTime() - startTime;
                } else {
                    final int updateCount = _stmt.getUpdateCount();
                    if (updateCount >= 0) {
                        session.print("affected " + updateCount + " rows");
                    } else {
                        session.print("ok.");
                    }
                }
                execTime = System.currentTimeMillis() - startTime;
                session.print(" (");
                if (lapTime > 0) {
                    session.print("first row: ");
                    if (session.printMessages()) {
                        TimeRenderer.printTime(lapTime, HenPlus.msg());
                    }
                    session.print("; total: ");
                }
                if (session.printMessages()) {
                    TimeRenderer.printTime(execTime, HenPlus.msg());
                }
                session.println(")");
            }

            // be smart and retrigger hashing of the tablenames.
            if ("drop".equals(cmd) || "create".equals(cmd)) {
                _tableCompleter.unhash(session);
            }

            return SUCCESS;
        } catch (final Exception e) {
            final String msg = e.getMessage();
            if (msg != null) {
                // oracle appends a newline to the message for some reason.
                Logger.error("FAILURE: '%s'", e.getMessage());
                Logger.debug("Exception: ", e);
            }

            return EXEC_FAILED;
        } finally {
            _statementCanceller.disarm();
            _longRunningDisplay.disarm();
            try {
                if (rset != null) {
                    rset.close();
                }
            } catch (final Exception e) {
            }
            try {
                if (_stmt != null) {
                    _stmt.close();
                }
            } catch (final Exception e) {
            }
            SigIntHandler.getInstance().popInterruptable();
        }
    }

    // very simple completer: try to determine wether we can complete a
    // table name. that is: if some keyword has been found before, switch to
    // table-completer-mode :-)
    @Override
    public Iterator complete(final CommandDispatcher disp, final String partialCommand, final String lastWord) {
        final String canonCmd = partialCommand.toUpperCase();
        /*
         * look for keywords that expect table names
         */
        int tableMatch = -1;
        for (int i = 0; i < TABLE_COMPLETER_KEYWORD.length; ++i) {
            final int match = canonCmd.indexOf(TABLE_COMPLETER_KEYWORD[i]);
            if (match >= 0) {
                tableMatch = match + TABLE_COMPLETER_KEYWORD[i].length();
                break;
            }
        }

        if (tableMatch < 0) {
            /*
             * ok, try to complete all columns from all tables since we don't
             * know yet what table the column will be from.
             */
            return _tableCompleter.completeAllColumns(lastWord);
        }

        int endTabMatch = -1; // where the table declaration ends.
        if (canonCmd.indexOf("UPDATE") >= 0) {
            endTabMatch = canonCmd.indexOf("SET");
        } else if (canonCmd.indexOf("INSERT") >= 0) {
            endTabMatch = canonCmd.indexOf("(");
        } else if (canonCmd.indexOf("WHERE") >= 0) {
            endTabMatch = canonCmd.indexOf("WHERE");
        } else if (canonCmd.indexOf("ORDER BY") >= 0) {
            endTabMatch = canonCmd.indexOf("ORDER BY");
        } else if (canonCmd.indexOf("GROUP BY") >= 0) {
            endTabMatch = canonCmd.indexOf("GROUP BY");
        }
        if (endTabMatch < 0) {
            endTabMatch = canonCmd.indexOf(";");
        }

        if (endTabMatch > tableMatch) {
            /*
             * column completion for the tables mentioned between in the table
             * area. This acknowledges as well aliases and prepends the names
             * with these aliases, if necessary.
             */
            final String tables = partialCommand.substring(tableMatch, endTabMatch);
            final HashMap<String, Set<String>> tmp = new HashMap<String, Set<String>>();
            final Iterator<Map.Entry<String, String>> it = tableDeclParser(tables).entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<String, String> entry = it.next();
                final String alias = entry.getKey();
                String tabName = entry.getValue();
                tabName = _tableCompleter.correctTableName(tabName);
                if (tabName == null) {
                    continue;
                }
                final Collection<String> columns = _tableCompleter.columnsFor(tabName);
                final Iterator<String> cit = columns.iterator();
                while (cit.hasNext()) {
                    final String col = cit.next();
                    Set<String> aliases = tmp.get(col);
                    if (aliases == null) {
                        aliases = new HashSet<String>();
                    }
                    aliases.add(alias);
                    tmp.put(col, aliases);
                }
            }
            final NameCompleter completer = new NameCompleter();
            final Iterator<Entry<String, Set<String>>> it2 = tmp.entrySet().iterator();
            while (it2.hasNext()) {
                final Map.Entry<String, Set<String>> entry = (Map.Entry) it.next();
                final String col = entry.getKey();
                final Set<String> aliases = entry.getValue();
                if (aliases.size() == 1) {
                    completer.addName(col);
                } else {
                    final Iterator<String> ait = aliases.iterator();
                    while (ait.hasNext()) {
                        completer.addName(ait.next() + "." + col);
                    }
                }
            }
            return completer.getAlternatives(lastWord);
        } else { // table completion.
            return _tableCompleter.completeTableName(HenPlus.getInstance().getCurrentSession(), lastWord);
        }
    }

    /**
     * parses 'tablename ((AS)? alias)? [,...]' and returns a map, that maps the names (or aliases) to the tablenames.
     */
    private Map tableDeclParser(final String tableDecl) {
        final StringTokenizer tokenizer = new StringTokenizer(tableDecl, " \t\n\r\f,", true);
        final Map result = new HashMap();
        String tok;
        String table = null;
        String alias = null;
        int state = 0;
        while (tokenizer.hasMoreElements()) {
            tok = tokenizer.nextToken();
            if (tok.length() == 1 && Character.isWhitespace(tok.charAt(0))) {
                continue;
            }
            switch (state) {
                case 0: { // initial/endstate
                    table = tok;
                    alias = tok;
                    state = 1;
                    break;
                }
                case 1: { // table seen, waiting for potential alias.
                    if ("AS".equals(tok.toUpperCase())) {
                        state = 2;
                    } else if (",".equals(tok)) {
                        state = 0; // we are done.
                    } else {
                        alias = tok;
                        state = 3;
                    }
                    break;
                }
                case 2: { // 'AS' seen, waiting definitly for alias.
                    if (",".equals(tok)) {
                        // error: alias missing for $table.
                        state = 0;
                    } else {
                        alias = tok;
                        state = 3;
                    }
                    break;
                }
                case 3: { // waiting for ',' at end of 'table (as)? alias'
                    if (!",".equals(tok)) {
                        // error: ',' expected.
                    }
                    state = 0;
                    break;
                }
            }

            if (state == 0) {
                result.put(alias, table);
            }
        }
        // store any unfinished state..
        if (state == 1 || state == 3) {
            result.put(alias, table);
        } else if (state == 2) {
            // error: alias expected for $table.
        }
        return result;
    }

    @Override
    public void shutdown() {
        _statementCanceller.stopThread();
    }

    @Override
    public String getSynopsis(String cmd) {
        cmd = cmd.toLowerCase();
        String syn = null;
        if ("select".equals(cmd)) {
            syn = "select <columns> from <table[s]> [ where <where-clause>]";
        } else if ("insert".equals(cmd)) {
            syn = "insert into <table> [(<columns>])] values (<values>)";
        } else if ("delete".equals(cmd)) {
            syn = "delete from <table> [ where <where-clause>]";
        } else if ("update".equals(cmd)) {
            syn = "update <table> set <column>=<value>[,...] [ where <where-clause> ]";
        } else if ("drop".equals(cmd)) {
            syn = "drop <table|index|view|alias|...>";
        } else if ("commit".equals(cmd)) {
            syn = cmd;
        } else if ("rollback".equals(cmd)) {
            syn = cmd;
        }
        return syn;
    }

    @Override
    public String getLongDescription(String cmd) {
        String dsc;
        dsc = "\t'" + cmd + "': this is not a build-in command, so would be\n"
                + "\tconsidered as SQL-command and handed over to the JDBC-driver.\n"
                + "\tHowever, I don't know anything about its syntax. RTFSQLM.\n"
                + "\ttry <http://www.google.com/search?q=sql+syntax+" + cmd + ">";
        cmd = cmd.toLowerCase();
        if ("select".equals(cmd)) {
            dsc = "\tselect from tables.";
        } else if ("delete".equals(cmd)) {
            dsc = "\tdelete data from tables. DML.";
        } else if ("insert".equals(cmd)) {
            dsc = "\tinsert data into tables. DML.";
        } else if ("update".equals(cmd)) {
            dsc = "\tupdate existing rows with new data. DML.";
        } else if ("create".equals(cmd)) {
            dsc = "\tcreate new database object (such as tables/views/indices..). DDL.";
        } else if ("alter".equals(cmd)) {
            dsc = "\talter a database object. DDL.";
        } else if ("drop".equals(cmd)) {
            dsc = "\tdrop (remove) a database object. DDL.";
        } else if ("rollback".equals(cmd)) {
            dsc = "\trollback transaction.";
        } else if ("commit".equals(cmd)) {
            dsc = "\tcommit transaction.";
        } else if ("call-procedure".equals(cmd)) {
            dsc = "\tcall a function that returns exactly one parameter\n" + "\tthat can be gathered as string (EXPERIMENTAL)\n"
                    + "\texample:\n" + "\t  call-procedure foobar(42);\n";
        }
        return dsc;
    }

    private class SQLColumnDelimiterProperty extends PropertyHolder {

        public SQLColumnDelimiterProperty() {
            super(SQLCommand.this.getColumnDelimiter());
        }

        @Override
        protected String propertyChanged(final String newValue) {
            SQLCommand.this.setColumnDelimiter(newValue);
            return newValue;
        }

        @Override
        public String getShortDescription() {
            return "modify column separator in query results";
        }

        @Override
        public String getDefaultValue() {
            return "|";
        }

        @Override
        public String getLongDescription() {
            String dsc;
            dsc = "\tSet another string that is used to separate columns in\n"
                    + "\tSQL result sets. Usually this is a pipe-symbol '|', but\n" + "\tmaybe you want to have an empty string ?";
            return dsc;
        }
    }

    private class RowLimitProperty extends PropertyHolder {

        public RowLimitProperty() {
            super(String.valueOf(SQLCommand.this.getRowLimit()));
        }

        @Override
        protected String propertyChanged(String newValue) throws Exception {
            newValue = newValue.trim();
            int newIntValue;
            try {
                newIntValue = Integer.parseInt(newValue);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("cannot parse '" + newValue + "' as integer");
            }
            if (newIntValue < 1) {
                throw new IllegalArgumentException("value cannot be less than 1");
            }
            SQLCommand.this.setRowLimit(newIntValue);
            return newValue;
        }

        @Override
        public String getDefaultValue() {
            return "2000";
        }

        @Override
        public String getShortDescription() {
            return "set the maximum number of rows printed";
        }
    }

    private class ShowHeaderProperty extends BooleanPropertyHolder {

        public ShowHeaderProperty() {
            super(true);
        }

        @Override
        public void booleanPropertyChanged(final boolean value) {
            setShowHeader(value);
        }

        @Override
        public String getDefaultValue() {
            return "on";
        }

        /**
         * return a short descriptive string.
         */
        @Override
        public String getShortDescription() {
            return "switches if header in selected tables should be shown";
        }
    }

    private class ShowFooterProperty extends BooleanPropertyHolder {

        public ShowFooterProperty() {
            super(true);
        }

        @Override
        public void booleanPropertyChanged(final boolean value) {
            setShowFooter(value);
        }

        @Override
        public String getDefaultValue() {
            return "on";
        }

        /**
         * return a short descriptive string.
         */
        @Override
        public String getShortDescription() {
            return "switches if footer in selected tables should be shown";
        }
    }
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
