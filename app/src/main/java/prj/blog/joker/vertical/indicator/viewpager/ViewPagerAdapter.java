package prj.blog.joker.vertical.indicator.viewpager;

import android.support.v4.view.PagerAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by XiaoYuLiu on 17/3/14.
 */

public class ViewPagerAdapter extends PagerAdapter {
    private List<FrameLayout> mList;

    public ViewPagerAdapter() {

    }

    public void setList(ArrayList<FrameLayout> list) {
        this.mList = list;
    }

    @Override
    public int getCount() {
        return mList.size() == 0 ? 0 : mList.size();
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
        FrameLayout frameLayout = mList.get(position);
        container.addView(frameLayout);
        return frameLayout;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView((View) object);
    }
}
