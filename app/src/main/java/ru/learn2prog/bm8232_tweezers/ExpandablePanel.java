package ru.learn2prog.bm8232_tweezers;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.LinearLayout;

public class ExpandablePanel extends LinearLayout {

    private final int mHandleId;
    private final int mContentId;
    private final int mViewGroupId;

    private final boolean isViewGroup;

    private View mHandle;
    private View mContent;
    private ViewGroup viewGroup;

    private boolean mExpanded = false;
    private int mCollapsedHeight = 0;
    private int mContentHeight = 0;
    private int mAnimationDuration = 0;

    private OnExpandListener mListener;

    public ExpandablePanel(Context context) {
        this(context, null);
    }

    public ExpandablePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mListener = new DefaultOnExpandListener();

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ExpandablePanel, 0, 0);

        // How high the content should be in "collapsed" state
        mCollapsedHeight = (int) a.getDimension(R.styleable.ExpandablePanel_collapsedHeight, 0.0f);

        // How long the animation should take
        mAnimationDuration = a.getInteger(R.styleable.ExpandablePanel_animationDuration, 500);

        int handleId = a.getResourceId(R.styleable.ExpandablePanel_handle, 0);
        if (handleId == 0) {
            throw new IllegalArgumentException(
                    "The handle attribute is required and must refer "
                            + "to a valid child.");
        }

        int contentId = a.getResourceId(R.styleable.ExpandablePanel_content, 0);
        if (contentId == 0) {
            throw new IllegalArgumentException("The content attribute is required and must refer to a valid child.");
        }

        int isViewGroupId = a.getResourceId(R.styleable.ExpandablePanel_isviewgroup, 0);
        int viewGroupId = a.getResourceId(R.styleable.ExpandablePanel_viewgroup, 0);
//        isViewGroup = findViewById(isViewGroupId);
        isViewGroup = a.getBoolean(R.styleable.ExpandablePanel_isviewgroup, false);
        if (isViewGroup) {
            mViewGroupId = viewGroupId;
        }
        else {
            mViewGroupId = 0;
        }

        mHandleId = handleId;
        mContentId = contentId;

        a.recycle();
    }

    public void setOnExpandListener(OnExpandListener listener) {
        mListener = listener;
    }

    public void setCollapsedHeight(int collapsedHeight) {
        mCollapsedHeight = collapsedHeight;
    }

    public void setAnimationDuration(int animationDuration) {
        mAnimationDuration = animationDuration;
    }

    public void hardCollapse() {
        Animation a;
        if (mExpanded) {
            a = new ExpandAnimation(mContentHeight, mCollapsedHeight);
            mListener.onCollapse(mHandle, mContent);

            a.setDuration(mAnimationDuration);
            if (mContent.getLayoutParams().height == 0) //Need to do this or else the animation will not play if the height is 0
            {
                android.view.ViewGroup.LayoutParams lp = mContent.getLayoutParams();
                lp.height = 1;
                mContent.setLayoutParams(lp);
                mContent.requestLayout();
            }
            mContent.startAnimation(a);
            mExpanded = !mExpanded;
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHandle = findViewById(mHandleId);
        if (mHandle == null) {
            throw new IllegalArgumentException(
                    "The handle attribute is must refer to an"
                            + " existing child.");
        }
        if(mViewGroupId != 0) {
            viewGroup = (ViewGroup) findViewById(mViewGroupId);
        }


        mContent = findViewById(mContentId);
        if (mContent == null) {
            throw new IllegalArgumentException(
                    "The content attribute must refer to an"
                            + " existing child.");
        }

        android.view.ViewGroup.LayoutParams lp = mContent.getLayoutParams();
        lp.height = mCollapsedHeight;
        mContent.setLayoutParams(lp);

        mHandle.setOnClickListener(new PanelToggler());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // First, measure how high content wants to be
        mContent.measure(widthMeasureSpec, MeasureSpec.UNSPECIFIED);
        mContentHeight = mContent.getMeasuredHeight();

        if (mContentHeight < mCollapsedHeight) {
            viewGroup.setVisibility(View.GONE);
//            mHandle.setVisibility(View.GONE);

        } else {
            viewGroup.setVisibility(View.VISIBLE);
//            mHandle.setVisibility(View.VISIBLE);
        }

        // Then let the usual thing happen
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private class PanelToggler implements OnClickListener {
        public void onClick(View v) {
            Animation a;
            //if (mExpanded) {
                // TODO TODO TODO !!!
                //a = new ExpandAnimation(mContentHeight, mCollapsedHeight);
                //mListener.onCollapse(mHandle, mContent);
            //} else {
                a = new ExpandAnimation(mCollapsedHeight, mContentHeight);
                mListener.onExpand(mHandle, mContent);
            //}
            a.setDuration(mAnimationDuration);
            if(mContent.getLayoutParams().height == 0) //Need to do this or else the animation will not play if the height is 0
            {
                android.view.ViewGroup.LayoutParams lp = mContent.getLayoutParams();
                lp.height = 1;
                mContent.setLayoutParams(lp);
                mContent.requestLayout();
            }
            if (!mExpanded) mContent.startAnimation(a);
            //mExpanded = !mExpanded;
            if (!mExpanded) mExpanded = true;
        }
    }

    private class ExpandAnimation extends Animation {
        private final int mStartHeight;
        private final int mDeltaHeight;

        public ExpandAnimation(int startHeight, int endHeight) {
            mStartHeight = startHeight;
            mDeltaHeight = endHeight - startHeight;
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            android.view.ViewGroup.LayoutParams lp = mContent.getLayoutParams();
            lp.height = (int) (mStartHeight + mDeltaHeight * interpolatedTime);
            mContent.setLayoutParams(lp);
        }

        @Override
        public boolean willChangeBounds() {
            return true;
        }
    }

    public interface OnExpandListener {
        public void onExpand(View handle, View content);
        public void onCollapse(View handle, View content);
    }

    private class DefaultOnExpandListener implements OnExpandListener {
        public void onCollapse(View handle, View content) {}
        public void onExpand(View handle, View content) {}
    }
}
