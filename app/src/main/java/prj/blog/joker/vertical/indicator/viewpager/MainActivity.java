package prj.blog.joker.vertical.indicator.viewpager;

import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private VerticalViewPager mVerticalViewPager;
    private Button mButton;
    private ArrayList<FrameLayout> mList;
    private ViewPagerAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mButton = bindView(R.id.indicator);
        mVerticalViewPager = bindView(R.id.viewpager);
        initData();
        mAdapter = new ViewPagerAdapter();
        mAdapter.setList(mList);
        mVerticalViewPager.setAdapter(mAdapter);


        mVerticalViewPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                mButton.setText("跟随vp脚步--"+position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });
    }

    private void initData() {
        mList = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            FrameLayout frameLayout = new FrameLayout(this);
            TextView textView = new TextView(this);
            textView.setText("我是number：" + i);
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            textView.setGravity(Gravity.CENTER);
            frameLayout.setLayoutParams(layoutParams);
            frameLayout.addView(textView);
            mList.add(frameLayout);
        }
    }

    public <T extends View> T bindView(int viewId) {
        return (T) findViewById(viewId);
    }

}
