package free.yhc.netmbuddy;

import android.os.Bundle;
import free.yhc.netmbuddy.model.YTSearchHelper;

public class YTVideoSearchAuthorActivity extends YTVideoSearchActivity {
    @Override
    protected YTSearchHelper.SearchType
    getSearchType() {
        return YTSearchHelper.SearchType.VID_AUTHOR;
    }

    @Override
    protected int
    getToolButtonSearchIcon() {
        return R.drawable.ic_ytsearch;
    }

    @Override
    protected String
    getTitlePrefix() {
        return (String)getResources().getText(R.string.author);
    }

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String text = getIntent().getStringExtra(MAP_KEY_SEARCH_TEXT);
        onCreateInternal(text, text);
    }
}